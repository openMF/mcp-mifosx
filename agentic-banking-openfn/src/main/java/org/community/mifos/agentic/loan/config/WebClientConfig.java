/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    RestClient.Builder restClientBuilder() {
        // Pin HTTP/1.1: the JDK default (HTTP/2) sends an h2c upgrade on plain http://,
        // which Lightning (Cowboy) accepts and the JDK client then fails to read.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory);
    }
}
