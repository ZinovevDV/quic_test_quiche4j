package ru.cbr.pis.quic_test_quiche4j.service;

import io.quiche4j.*;
import io.quiche4j.http3.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;

import java.util.Arrays;
@AllArgsConstructor
@Slf4j
public class QuicClient {
    private final String clientName;

    private final int port;

    @Getter
    private final String host;

    private final String chainFileName;

    private final String privateKeyFileName;

    private final int maxDatagramSize;

    public void sendMessage(String messsage) throws IOException {

        final String url = host + ":" + port;

        URI uri;

        try {
            uri = new URI(url);
            log.info("> sending request to " + uri);
        } catch (URISyntaxException e) {
            log.error("Failed to parse URL " + url);
            System.exit(1);
            return;
        }

        final InetAddress address = InetAddress.getByName(uri.getHost());

        final Config config = getConfig();

        final byte[] connId = Quiche.newConnectionId();
        final Connection conn = Quiche.connect(uri.getHost(), connId, config);

        final byte[] buffer = new byte[maxDatagramSize];
        int len = conn.send(buffer);
        if (len < 0 && len != Quiche.ErrorCode.DONE) {
            log.error("! handshake init problem " + len);
            System.exit(1);
            return;
        }
        log.info("> handshake size: " + len);

        final DatagramPacket handshakePacket = new DatagramPacket(buffer, len, address, port);
        final DatagramSocket socket = new DatagramSocket(0);
        socket.setSoTimeout(200);
        socket.send(handshakePacket);

        DatagramPacket packet;

        receiveFromServer(socket, conn);

        byte[] sendBuffer = new byte[maxDatagramSize];
        sendBuffer = Arrays.copyOf(messsage.getBytes(), maxDatagramSize);

        len = conn.send(sendBuffer);
        log.info("> conn.send " + len + " bytes");


        packet = new DatagramPacket(sendBuffer, sendBuffer.length, address, port);
        socket.send(packet);

        receiveFromServer(socket, conn);

        log.info("> conn is closed");
        log.info(String.valueOf(conn.stats()));
        socket.close();

    } //sendMessage

    public void run() throws IOException, URISyntaxException {
        sendMessage("test message");
    }

    private Config getConfig(){
        return new ConfigBuilder(Quiche.PROTOCOL_VERSION)
                .withApplicationProtos(Http3.APPLICATION_PROTOCOL)
                // CAUTION: this should not be set to `false` in production
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
                .build();
    }

    private void receiveFromServer(DatagramSocket socket, Connection conn) throws IOException {
        byte[] receiveBuffer = new byte[maxDatagramSize];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        while (true) {
            try {
                socket.receive(packet);
                final int receiveBytes = packet.getLength();
                log.info("socket receive " + receiveBytes + " bytes");

                final int read = conn.recv(Arrays.copyOfRange(packet.getData(), packet.getOffset(), receiveBytes));

                if (read < 0 && read != Quiche.ErrorCode.DONE) {
                    log.info("conn receive failed");
                    break;
                } else {
                    log.info("conn receive " + read + " bytes");
                }
            } catch (SocketTimeoutException e) {
                conn.onTimeout();
                break;
            }
        }
    }

}
