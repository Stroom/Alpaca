package com.example.alpaca.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Alpaca connection settings. Defaults point at the sandbox environment.
 *
 * @param clientId          OAuth2 client id (from the Broker dashboard)
 * @param clientSecret      OAuth2 client secret
 * @param tokenUrl          OAuth2 token endpoint (client_credentials grant)
 * @param brokerBaseUrl     Broker API base URL
 * @param marketDataBaseUrl Market Data API base URL
 * @param correspondent     4-character correspondent code (used to fund accounts via simulated wires)
 * @param marketDataFeed    stock data feed: iex (free) or sip
 * @param events            background event stream settings
 */
@Validated
@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String tokenUrl,
        @NotBlank String brokerBaseUrl,
        @NotBlank String marketDataBaseUrl,
        String correspondent,
        @DefaultValue("iex") String marketDataFeed,
        @DefaultValue Events events) {

    public record Events(@DefaultValue("true") boolean enabled, @DefaultValue("1") int initialLookbackDays) {
    }
}
