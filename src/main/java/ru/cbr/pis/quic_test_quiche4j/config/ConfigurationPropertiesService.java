package ru.cbr.pis.quic_test_quiche4j.config;

import jdk.jfr.StackTrace;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "service")
@Getter
@Setter
public class ConfigurationPropertiesService {
    /**
     * Публичный ключ (сертификат)
     */
    private String clientCertiicateKeyFileName;

    /**
     * Приватный ключ
     */
    private String clientPrivateKeyFileName;

    /**
     * Размер буфера
     */
    private int clientMaxDatagramSize;

    /**
     * Имя клиента
     */
    private String clientUserAgent;

    /**
     * Адрес сервера получателя
     */
    private String clientReceiverHost;
    /**
     * Порт сервера получателя
     */
    private int clientReceiverPort;

    /**
     * Публичный ключ (сертификат)
     */
    private String serverCertiicateKeyFileName;

    /**
     * Приватный ключ
     */
    private String serverPrivateKeyFileName;

    /**
     * Размер буфера
     */
    private int serverMaxDatagramSize;

    /**
     * Имя сервера
     */
    private String serverName;

    /**
     * Заголовок сервера
     */
    private String serverHeaderName;

    /**
     * Адрес сервера
     */
    private String serverHost;
    /**
     * Порт сервера
     */
    private int serverPort;
}
