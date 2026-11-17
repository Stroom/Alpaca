package com.example.alpaca.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

@Configuration
public class AlpacaClientConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AlpacaTokenProvider alpacaTokenProvider(AlpacaProperties properties, RestClient.Builder builder, Clock clock) {
        return new AlpacaTokenProvider(properties, builder.clone(), clock);
    }

    @Bean
    RestClient brokerRestClient(AlpacaProperties properties, RestClient.Builder builder, AlpacaTokenProvider tokens) {
        return builder.clone()
                .baseUrl(properties.brokerBaseUrl())
                .requestInterceptor(bearerAuth(tokens))
                .build();
    }

    @Bean
    RestClient marketDataRestClient(AlpacaProperties properties, RestClient.Builder builder, AlpacaTokenProvider tokens) {
        return builder.clone()
                .baseUrl(properties.marketDataBaseUrl())
                .requestInterceptor(bearerAuth(tokens))
                .build();
    }

    /** Adds the bearer token; on a 401 refreshes the token once and retries. */
    private static ClientHttpRequestInterceptor bearerAuth(AlpacaTokenProvider tokens) {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(tokens.getAccessToken());
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                response.close();
                tokens.invalidate();
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.getAccessToken());
                return execution.execute(request, body);
            }
            return response;
        };
    }
}
