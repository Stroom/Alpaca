package com.example.alpaca.marketdata;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Thin client over the Alpaca Market Data API (see market-data-api.json).
 * Query parameters (symbols, timeframe, start, end, feed, limit, page_token, ...) are forwarded as-is.
 */
@Component
public class MarketDataClient {

    private final RestClient rest;

    public MarketDataClient(@Qualifier("marketDataRestClient") RestClient rest) {
        this.rest = rest;
    }

    // ---- Stocks: multi-symbol (symbols=AAPL,MSFT) ----

    public JsonNode latestStockQuotes(MultiValueMap<String, String> query) {
        return get("/v2/stocks/quotes/latest", query);
    }

    public JsonNode latestStockTrades(MultiValueMap<String, String> query) {
        return get("/v2/stocks/trades/latest", query);
    }

    public JsonNode latestStockBars(MultiValueMap<String, String> query) {
        return get("/v2/stocks/bars/latest", query);
    }

    public JsonNode stockSnapshots(MultiValueMap<String, String> query) {
        return get("/v2/stocks/snapshots", query);
    }

    public JsonNode stockBars(MultiValueMap<String, String> query) {
        return get("/v2/stocks/bars", query);
    }

    // ---- Stocks: single symbol ----

    public JsonNode stockSnapshot(String symbol, MultiValueMap<String, String> query) {
        return get("/v2/stocks/{symbol}/snapshot", query, symbol);
    }

    public JsonNode latestStockQuote(String symbol, MultiValueMap<String, String> query) {
        return get("/v2/stocks/{symbol}/quotes/latest", query, symbol);
    }

    public JsonNode latestStockTrade(String symbol, MultiValueMap<String, String> query) {
        return get("/v2/stocks/{symbol}/trades/latest", query, symbol);
    }

    // ---- Crypto (loc = us, us-1, eu-1) ----

    public JsonNode latestCryptoQuotes(String loc, MultiValueMap<String, String> query) {
        return get("/v1beta3/crypto/{loc}/latest/quotes", query, loc);
    }

    public JsonNode cryptoSnapshots(String loc, MultiValueMap<String, String> query) {
        return get("/v1beta3/crypto/{loc}/snapshots", query, loc);
    }

    public JsonNode cryptoBars(String loc, MultiValueMap<String, String> query) {
        return get("/v1beta3/crypto/{loc}/bars", query, loc);
    }

    private JsonNode get(String path, MultiValueMap<String, String> query, Object... uriVars) {
        return rest.get()
                .uri(b -> b.path(path).queryParams(query).build(uriVars))
                .retrieve()
                .body(JsonNode.class);
    }
}
