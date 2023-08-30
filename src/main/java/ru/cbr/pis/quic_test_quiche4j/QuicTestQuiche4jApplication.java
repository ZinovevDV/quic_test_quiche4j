package ru.cbr.pis.quic_test_quiche4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import ru.cbr.pis.quic_test_quiche4j.service.QuicClient;
import ru.cbr.pis.quic_test_quiche4j.service.QuicServer;

import java.io.IOException;
import java.net.URISyntaxException;

@SpringBootApplication
public class QuicTestQuiche4jApplication {

	public static void main(String[] args) throws IOException, URISyntaxException {

		var context = SpringApplication.run(QuicTestQuiche4jApplication.class, args);

		//context.getBean("quicClient", QuicClient.class).run();
		context.getBean("quicServer", QuicServer.class).run();
	}

}
