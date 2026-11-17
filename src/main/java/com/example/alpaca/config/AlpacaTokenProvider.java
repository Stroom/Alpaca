package com.example.alpaca.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Obtains and caches an OAuth2 access token using the client_credentials grant
 * (POST {tokenUrl}, form-encoded). Tokens are refreshed shortly before they expire.
 */
public class AlpacaTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(AlpacaTokenProvider.class);
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    private final AlpacaProperties properties;
    private final RestClient tokenClient;
    private final Clock clock;

    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public AlpacaTokenProvider(AlpacaProperties properties, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.tokenClient = builder.build();
        this.clock = clock;
    }

    public synchronized String getAccessToken() {
        if (accessToken == null || clock.instant().isAfter(expiresAt.minus(REFRESH_SKEW))) {
            refresh();
        }
        return accessToken;
    }

    /** Drops the cached token so the next call fetches a new one (e.g. after a 401). */
    public synchronized void invalidate() {
        accessToken = null;
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        TokenResponse response = tokenClient.post()
                .uri(properties.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Alpaca token endpoint returned no access_token");
        }
        accessToken = response.accessToken();
        expiresAt = clock.instant().plusSeconds(response.expiresIn());
        log.debug("Obtained Alpaca access token, expires in {}s", response.expiresIn());
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType) {
    }
}
