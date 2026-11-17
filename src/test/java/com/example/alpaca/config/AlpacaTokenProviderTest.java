package com.example.alpaca.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AlpacaTokenProviderTest {

    private static final String TOKEN_URL = "https://authx.example.test/v1/oauth2/token";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private MockRestServiceServer server;
    private AlpacaTokenProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var props = new AlpacaProperties("CLIENTID", "secret", TOKEN_URL, "https://broker", "https://data", null, "iex", new AlpacaProperties.Events(false, 1));
        provider = new AlpacaTokenProvider(props, builder, clock);
    }

    @Test
    void fetchesTokenWithClientCredentialsAndCachesIt() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "client_credentials",
                        "client_id", "CLIENTID",
                        "client_secret", "secret")))
                .andRespond(withSuccess("""
                        {"access_token":"tok-1","expires_in":900,"token_type":"Bearer"}""",
                        MediaType.APPLICATION_JSON));

        assertThat(provider.getAccessToken()).isEqualTo("tok-1");
        now.set(now.get().plusSeconds(600));
        assertThat(provider.getAccessToken()).isEqualTo("tok-1");
        server.verify();
    }

    @Test
    void refreshesTokenShortlyBeforeExpiry() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                {"access_token":"tok-1","expires_in":900,"token_type":"Bearer"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                {"access_token":"tok-2","expires_in":900,"token_type":"Bearer"}""", MediaType.APPLICATION_JSON));

        assertThat(provider.getAccessToken()).isEqualTo("tok-1");
        now.set(now.get().plusSeconds(850));
        assertThat(provider.getAccessToken()).isEqualTo("tok-2");
        server.verify();
    }
}
