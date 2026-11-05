package ru.tsu.accounts.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpRedirectConfig {
    @Bean
    @ConditionalOnProperty(name = "account.http.enabled", havingValue = "true")
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> redirectConnector(
        @Value("${account.http.address:127.0.0.1}") String httpAddress,
        @Value("${account.http.port:8080}") int httpPort,
        @Value("${server.port:8443}") int httpsPort) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(httpPort);
            connector.setProperty("address", httpAddress);
            connector.setScheme("http");
            connector.setSecure(false);
            connector.setRedirectPort(httpsPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
