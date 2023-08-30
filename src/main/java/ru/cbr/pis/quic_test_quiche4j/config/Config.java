package ru.cbr.pis.quic_test_quiche4j.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.cbr.pis.quic_test_quiche4j.service.QuicClient;
import ru.cbr.pis.quic_test_quiche4j.service.QuicServer;


@Configuration
@EnableAutoConfiguration
@RequiredArgsConstructor
@EnableConfigurationProperties(ConfigurationPropertiesService.class)
public class Config {
    private final ConfigurationPropertiesService propertiesService;

    @Bean
    QuicClient quicClient(){
        return new QuicClient(propertiesService.getClientUserAgent(),
                propertiesService.getClientReceiverPort(), propertiesService.getClientReceiverHost(),
                propertiesService.getClientCertiicateKeyFileName(), propertiesService.getClientPrivateKeyFileName(),
                propertiesService.getClientMaxDatagramSize());
    }

    @Bean
    QuicServer quicServer(){
        return new QuicServer(propertiesService.getServerMaxDatagramSize(),  propertiesService.getServerName(), propertiesService.getServerHeaderName(),
                propertiesService.getServerCertiicateKeyFileName(), propertiesService.getServerPrivateKeyFileName(),
                propertiesService.getServerPort(), propertiesService.getServerHost());
    }
}
