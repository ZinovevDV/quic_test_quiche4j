package ru.cbr.pis.quic_test_quiche4j.service;

import io.quiche4j.*;
import io.quiche4j.http3.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class QuicServer {
    protected final static class PartialResponse {
        protected List<Http3Header> headers;
        protected byte[] body;
        protected long written;

        PartialResponse(List<Http3Header> headers, byte[] body, long written) {
            this.headers = headers;
            this.body = body;
            this.written = written;
        }
    }

    protected final static class Client {

        private final Connection conn;
        private Http3Connection h3Conn;
        private HashMap<Long, PartialResponse> partialResponses;
        private SocketAddress sender;

        public Client(Connection conn, SocketAddress sender) {
            this.conn = conn;
            this.sender = sender;
            this.h3Conn = null;
            this.partialResponses = new HashMap<>();
        }

        public final Connection connection() {
            return this.conn;
        }

        public final SocketAddress sender() {
            return this.sender;
        }

        public final Http3Connection http3Connection() {
            return this.h3Conn;
        }

        public final void setHttp3Connection(Http3Connection conn) {
            this.h3Conn = conn;
        }

    }
    private static final String HEADER_NAME_STATUS = ":status";
    private static final String HEADER_NAME_CONTENT_LENGTH = "content-length";

    private final int maxDatagramSize;
    private final String serverName;
    private final String headerServerName;

    private final String chainFileName;

    private final String privateKeyFileName;

    private final int port;

    private final String hostname;

    private final byte[] serverNameBytes;

    private final int  serverNameBytesLen;

    @Autowired
    private QuicClient quicClient;
    public QuicServer(int maxDatagramSize, String serverName, String headerServerName,
                      String chainFileName, String privateKeyFileName,
                      int port, String hostname){
        this.maxDatagramSize = maxDatagramSize;
        this.serverName = serverName;
        this.serverNameBytes = serverName.getBytes();
        this.serverNameBytesLen = this.serverNameBytes.length;
        this.headerServerName = headerServerName;
        this.chainFileName = chainFileName;
        this.privateKeyFileName = privateKeyFileName;
        this.port = port;
        this.hostname = hostname;
    }

    public void run() throws IOException {
        final byte[] buf = new byte[65535];
        final byte[] out = new byte[maxDatagramSize];

        final Config config = getConfig();

        final DatagramSocket socket = new DatagramSocket(port, InetAddress.getByName(hostname));
        socket.setSoTimeout(100);

        final Http3Config h3Config = new Http3ConfigBuilder().build();
        final byte[] connIdSeed = Quiche.newConnectionIdSeed();
        final HashMap<String, Client> clients = new HashMap<>();
        final AtomicBoolean running = new AtomicBoolean(true);

        log.info(String.format("! listening on %s:%d", hostname, port));

        while (running.get()) {
            // READING
            while (true) {
                final DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (IOException e) {
                    // TIMERS
                    for (Client client : clients.values()) {
                        client.connection().onTimeout();
                    }
                    break;
                }

                final int offset = packet.getOffset();
                final int len = packet.getLength();
                // xxx(okachaiev): can we avoid doing copy here?
                final byte[] packetBuf = Arrays.copyOfRange(packet.getData(), offset, len);

                log.info("> socket.recv " + len + " bytes");

                // PARSE QUIC HEADER
                final PacketHeader hdr;
                try {
                    hdr = PacketHeader.parse(packetBuf, Quiche.MAX_CONN_ID_LEN);
                    log.info("> packet " + hdr);
                } catch (Exception e) {
                    log.info("! failed to parse headers " + e);
                    continue;
                }

                // SIGN CONN ID
                final byte[] connId = Quiche.signConnectionId(connIdSeed, hdr.destinationConnectionId());
                Client client = clients.get(Utils.asHex(hdr.destinationConnectionId()));
                if (null == client)
                    client = clients.get(Utils.asHex(connId));
                if (null == client) {
                    // CREATE CLIENT IF MISSING
                    if (PacketType.INITIAL != hdr.packetType()) {
                        log.info("! wrong packet type");
                        continue;
                    }

                    // NEGOTIATE VERSION
                    if (!Quiche.versionIsSupported(hdr.version())) {
                        log.info("> version negotiation");

                        final int negLength = Quiche.negotiateVersion(hdr.sourceConnectionId(),
                                hdr.destinationConnectionId(), out);
                        if (negLength < 0) {
                            log.info("! failed to negotiate version " + negLength);
                            System.exit(1);
                            return;
                        }
                        final DatagramPacket negPacket = new DatagramPacket(out, negLength, packet.getAddress(),
                                packet.getPort());
                        socket.send(negPacket);
                        continue;
                    }

                    // RETRY IF TOKEN IS EMPTY
                    if (null == hdr.token()) {
                        log.info("> stateless retry");

                        final byte[] token = mintToken(hdr, packet.getAddress());
                        final int retryLength = Quiche.retry(hdr.sourceConnectionId(), hdr.destinationConnectionId(),
                                connId, token, hdr.version(), out);
                        if (retryLength < 0) {
                            log.info("! retry failed " + retryLength);
                            System.exit(1);
                            return;
                        }

                        log.info("> retry length " + retryLength);

                        final DatagramPacket retryPacket = new DatagramPacket(out, retryLength, packet.getAddress(),
                                packet.getPort());
                        socket.send(retryPacket);
                        continue;
                    }

                    // VALIDATE TOKEN
                    final byte[] odcid = validateToken(packet.getAddress(), hdr.token());
                    if (null == odcid) {
                        log.info("! invalid address validation token");
                        continue;
                    }

                    byte[] sourceConnId = connId;
                    final byte[] destinationConnId = hdr.destinationConnectionId();
                    if (sourceConnId.length != destinationConnId.length) {
                        log.info("! invalid destination connection id");
                        continue;
                    }
                    sourceConnId = destinationConnId;

                    final Connection conn = Quiche.accept(sourceConnId, odcid, config);

                    log.info("> new connection " + Utils.asHex(sourceConnId));

                    client = new Client(conn, packet.getSocketAddress());
                    clients.put(Utils.asHex(sourceConnId), client);

                    log.info("! # of clients: " + clients.size());
                }

                // POTENTIALLY COALESCED PACKETS
                final Connection conn = client.connection();
                final int read = conn.recv(packetBuf);
                if (read < 0 && read != Quiche.ErrorCode.DONE) {
                    log.info("> recv failed " + read);
                    break;
                }
                if (read <= 0)
                    break;

                log.info("> conn.recv " + read + " bytes");
                log.info("> conn.established " + conn.isEstablished());

                // ESTABLISH H3 CONNECTION IF NONE
                Http3Connection h3Conn = client.http3Connection();
                if ((conn.isInEarlyData() || conn.isEstablished()) && null == h3Conn) {
                    log.info("> handshake done " + conn.isEstablished());
                    h3Conn = Http3Connection.withTransport(conn, h3Config);
                    client.setHttp3Connection(h3Conn);

                    log.info("> new H3 connection " + h3Conn);
                }

                if (null != h3Conn) {
                    // PROCESS WRITABLES
                    final Client current = client;
                    client.connection().writable().forEach(streamId -> {
                        handleWritable(current, streamId);
                    });

                    // H3 POLL
                    h3poll(h3Conn, current);
                }
            }

            // WRITES
            writes(clients, socket);

            // CLEANUP CLOSED CONNS
            cleanClients(clients);

            // BACK TO READING
        }

        log.info("> server stopped");
        socket.close();
    }

    private void writes(final HashMap<String, QuicServer.Client> clients,  DatagramSocket socket) throws IOException {
        int len = 0;
        byte[] out = new byte[maxDatagramSize];
    //    if( clients.size() > 0){
    //        if(!quicClient.getHost().equals(hostname))
    //            quicClient.sendMessage("I'm ok");
    //    }
        for (Client client : clients.values()) {
            final Connection conn = client.connection();

            while (true) {
                len = conn.send(out);
                if (len < 0 && len != Quiche.ErrorCode.DONE) {
                    log.info("! conn.send failed " + len);
                    break;
                }
                if (len <= 0)
                    break;
                log.info("> conn.send " + len + " bytes");
                final DatagramPacket packet = new DatagramPacket(out, len, client.sender());
                socket.send(packet);
            }
        }

    }

    private void cleanClients(HashMap<String, QuicServer.Client> clients){
        for (String connId : clients.keySet()) {
            if (clients.get(connId).connection().isClosed()) {
                log.info("> cleaning up " + connId);

                clients.remove(connId);

                log.info("! # of clients: " + clients.size());
            }
        }
    }

    private void h3poll(Http3Connection h3Conn,  QuicServer.Client client){
        while (true) {
            final long streamId = h3Conn.poll(new Http3EventListener() {
                public void onHeaders(long streamId, List<Http3Header> headers, boolean hasBody) {
                    headers.forEach(header -> {
                        log.info("< got header " + header.name() + " on " + streamId);
                    });
                    handleRequest(client, streamId, headers);
                }

                public void onData(long streamId) {
                    log.info("< got data on " + streamId);
                }

                public void onFinished(long streamId) {
                    log.info("< finished " + streamId);
                }
            });

            if (streamId < 0 && streamId != Quiche.ErrorCode.DONE) {
                log.info("! poll failed " + streamId);

                // xxx(okachaiev): this should actially break from 2 loops
                break;
            }
            // xxx(okachaiev): this should actially break from 2 loops
            if (Quiche.ErrorCode.DONE == streamId)
                break;

            log.info("< poll " + streamId);
        }
    }

    public final byte[] mintToken(PacketHeader hdr, InetAddress address) {
        final byte[] addr = address.getAddress();
        final byte[] dcid = hdr.destinationConnectionId();
        final int total = serverNameBytesLen + addr.length + dcid.length;
        final ByteBuffer buf = ByteBuffer.allocate(total);
        buf.put(serverNameBytes);
        buf.put(addr);
        buf.put(dcid);
        return buf.array();
    }

    public final byte[] validateToken(InetAddress address, byte[] token) {
        if (token.length <= 8)
            return null;
        if (!Arrays.equals(serverNameBytes, Arrays.copyOfRange(token, 0, serverNameBytesLen)))
            return null;
        final byte[] addr = address.getAddress();
        if (!Arrays.equals(addr, Arrays.copyOfRange(token, serverNameBytesLen, addr.length + serverNameBytesLen)))
            return null;
        return Arrays.copyOfRange(token, serverNameBytesLen + addr.length, token.length);
    }

    public final void handleRequest(Client client, Long streamId, List<Http3Header> req) {
        log.info("< request " + streamId);

        final Connection conn = client.connection();
        final Http3Connection h3Conn = client.http3Connection();

        // SHUTDOWN STREAM
        conn.streamShutdown(streamId, Quiche.Shutdown.READ, 0L);

        final byte[] body = "Hello world".getBytes();
        final List<Http3Header> headers = new ArrayList<>();
        headers.add(new Http3Header(HEADER_NAME_STATUS, "200"));
        headers.add(new Http3Header(headerServerName, serverName));
        headers.add(new Http3Header(HEADER_NAME_CONTENT_LENGTH, Integer.toString(body.length)));

        final long sent = h3Conn.sendResponse(streamId, headers, false);
        if (sent == Http3.ErrorCode.STREAM_BLOCKED) {
            // STREAM BLOCKED
            System.out.print("> stream " + streamId + " blocked");

            // STASH PARTIAL RESPONSE
            final PartialResponse part = new PartialResponse(headers, body, 0L);
            client.partialResponses.put(streamId, part);
            return;
        }

        if (sent < 0) {
            log.info("! h3.send response failed " + sent);
            return;
        }

        final long written = h3Conn.sendBody(streamId, body, true);
        if (written < 0) {
            log.info("! h3 send body failed " + written);
            return;
        }

        log.info("> send body " + written + " body");

        if (written < body.length) {
            // STASH PARTIAL RESPONSE
            final PartialResponse part = new PartialResponse(null, body, written);
            client.partialResponses.put(streamId, part);
        }
    }

    public final void handleWritable(Client client, long streamId) {
        final PartialResponse resp = client.partialResponses.get(streamId);
        if (null == resp)
            return;

        final Http3Connection h3 = client.http3Connection();
        if (null != resp.headers) {
            final long sent = h3.sendResponse(streamId, resp.headers, false);
            if (sent == Http3.ErrorCode.STREAM_BLOCKED)
                return;
            if (sent < 0) {
                log.info("! h3.send response failed " + sent);
                return;
            }
        }

        resp.headers = null;

        final byte[] body = Arrays.copyOfRange(resp.body, (int) resp.written, resp.body.length);
        final long written = h3.sendBody(streamId, body, true);
        if (written < 0 && written != Quiche.ErrorCode.DONE) {
            log.info("! h3 send body failed " + written);
            return;
        }

        log.info("> send body " + written + " body");

        resp.written += written;
        if (resp.written < resp.body.length) {
            client.partialResponses.remove(streamId);
        }
    }

    private Config getConfig(){
        return new ConfigBuilder(Quiche.PROTOCOL_VERSION)
                .withApplicationProtos(Http3.APPLICATION_PROTOCOL)
                .withVerifyPeer(true)
                .loadCertChainFromPemFile(chainFileName)
                .loadPrivKeyFromPemFile(privateKeyFileName)
                .withMaxIdleTimeout(5_000)
                .withMaxUdpPayloadSize(maxDatagramSize)
                .withInitialMaxData(10_000_000)
                .withInitialMaxStreamDataBidiLocal(1_000_000)
                .withInitialMaxStreamDataBidiRemote(1_000_000)
                .withInitialMaxStreamDataUni(1_000_000)
                .withInitialMaxStreamsBidi(100)
                .withInitialMaxStreamsUni(100)
                .withDisableActiveMigration(true)
                .enableEarlyData()
                .build();
    }
}
