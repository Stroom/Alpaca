package com.example.alpaca.broker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Thin client over the Alpaca Broker API (see broker-api.json). Request and response bodies
 * are passed through as JSON so the full API surface is available without hand-written DTOs.
 */
@Component
public class BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(BrokerClient.class);

    private final RestClient rest;

    public BrokerClient(@Qualifier("brokerRestClient") RestClient rest) {
        this.rest = rest;
    }

    // ---- Accounts ----

    public JsonNode listAccounts(MultiValueMap<String, String> query) {
        return get("/v1/accounts", query);
    }

    public JsonNode createAccount(JsonNode account) {
        return post("/v1/accounts", account);
    }

    /** Sandbox only: simulates an incoming wire that credits the account identified in the FFC instructions. */
    public JsonNode createIncomingWire(JsonNode wire) {
        return post("/v1/testing/incoming_wires", wire);
    }

    public JsonNode getAccount(String accountId) {
        return get("/v1/accounts/{accountId}", null, accountId);
    }

    public JsonNode getTradingAccount(String accountId) {
        return get("/v1/trading/accounts/{accountId}/account", null, accountId);
    }

    // ---- Orders ----

    public JsonNode createOrder(String accountId, JsonNode order) {
        return rest.post()
                .uri("/v1/trading/accounts/{accountId}/orders", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(order)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode listOrders(String accountId, MultiValueMap<String, String> query) {
        return get("/v1/trading/accounts/{accountId}/orders", query, accountId);
    }

    public JsonNode getOrder(String accountId, String orderId) {
        return get("/v1/trading/accounts/{accountId}/orders/{orderId}", null, accountId, orderId);
    }

    public JsonNode getOrderByClientOrderId(String accountId, String clientOrderId) {
        return rest.get()
                .uri(b -> b.path("/v1/trading/accounts/{accountId}/orders:by_client_order_id")
                        .queryParam("client_order_id", clientOrderId)
                        .build(accountId))
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode replaceOrder(String accountId, String orderId, JsonNode changes) {
        return rest.patch()
                .uri("/v1/trading/accounts/{accountId}/orders/{orderId}", accountId, orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(changes)
                .retrieve()
                .body(JsonNode.class);
    }

    public void cancelOrder(String accountId, String orderId) {
        rest.delete()
                .uri("/v1/trading/accounts/{accountId}/orders/{orderId}", accountId, orderId)
                .retrieve()
                .toBodilessEntity();
    }

    public JsonNode cancelAllOrders(String accountId) {
        return rest.delete()
                .uri("/v1/trading/accounts/{accountId}/orders", accountId)
                .retrieve()
                .body(JsonNode.class);
    }

    // ---- Positions ----

    public JsonNode listPositions(String accountId) {
        return get("/v1/trading/accounts/{accountId}/positions", null, accountId);
    }

    public JsonNode getPosition(String accountId, String symbolOrAssetId) {
        return get("/v1/trading/accounts/{accountId}/positions/{symbol}", null, accountId, symbolOrAssetId);
    }

    // ---- Reference data ----

    public JsonNode listAssets(MultiValueMap<String, String> query) {
        return get("/v1/assets", query);
    }

    public JsonNode getAsset(String symbolOrAssetId) {
        return get("/v1/assets/{symbol}", null, symbolOrAssetId);
    }

    public JsonNode getClock() {
        return get("/v1/clock", null);
    }

    public JsonNode getCalendar(MultiValueMap<String, String> query) {
        return get("/v1/calendar", query);
    }

    // ---- Events (SSE) ----

    /** One server-sent event from the upstream stream. */
    public record SseEvent(String id, String event, String data) {
    }

    /** Receives upstream events; throwing an exception stops the stream and closes the upstream connection. */
    @FunctionalInterface
    public interface SseListener {
        void onEvent(SseEvent event) throws IOException;
    }

    /**
     * Subscribes to the trade events SSE feed (/v2/events/trades: new, fill, partial_fill, canceled, ...)
     * and calls {@code listener} for each event. Blocks until the upstream closes or the listener throws.
     */
    public void streamTradeEvents(MultiValueMap<String, String> query, Runnable onConnected, SseListener listener) {
        streamEvents("/v2/events/trades", query, onConnected, listener);
    }

    /**
     * Subscribes to any Broker API SSE endpoint (e.g. /v2/events/trades, /v2beta1/events/activities,
     * /v2/events/funding/status). Blocks until the upstream closes or the listener throws.
     */
    public void streamEvents(String path, MultiValueMap<String, String> query, Runnable onConnected, SseListener listener) {
        rest.get()
                .uri(b -> withQuery(b.path(path), query).build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new BrokerStreamException(response.getStatusCode().value(),
                                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    }
                    log.info("Connected to Alpaca event stream {}", path);
                    onConnected.run();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        readEvents(reader, listener);
                    }
                    return null;
                }, true);
    }

    /** Minimal SSE parser (id/event/data fields, blank line terminates an event, ':' lines are comments). */
    static void readEvents(BufferedReader reader, SseListener listener) throws IOException {
        String id = null;
        String event = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    listener.onEvent(new SseEvent(id, event, data.toString()));
                }
                event = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "id" -> id = value;
                case "event" -> event = value;
                case "data" -> {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                default -> { }
            }
        }
    }

    private JsonNode post(String path, JsonNode body) {
        return rest.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode get(String path, MultiValueMap<String, String> query, Object... uriVars) {
        return rest.get()
                .uri(b -> withQuery(b.path(path), query).build(uriVars))
                .retrieve()
                .body(JsonNode.class);
    }

    private static UriBuilder withQuery(UriBuilder builder, MultiValueMap<String, String> query) {
        if (query != null) {
            builder.queryParams(query);
        }
        return builder;
    }

    public static class BrokerStreamException extends RuntimeException {
        private final int status;

        public BrokerStreamException(int status, String body) {
            super("Trade events stream failed with HTTP " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
