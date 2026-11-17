package com.example.alpaca.web;

import com.example.alpaca.marketdata.MarketDataClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Instrument prices via the Alpaca Market Data API. All query params are forwarded,
 * e.g. ?symbols=AAPL,MSFT&feed=iex or ?symbols=AAPL&timeframe=1Day&start=2026-01-01.
 */
@RestController
@RequestMapping("/api/market")
@Tag(name = "Market data", description = "Quotes, trades, bars and snapshots via the Alpaca Market Data API")
public class MarketDataController {

    private final MarketDataClient marketData;

    public MarketDataController(MarketDataClient marketData) {
        this.marketData = marketData;
    }

    // ---- Stocks: multi-symbol ----

    @GetMapping("/stocks/quotes/latest")
    @Operation(summary = "Latest quotes", description = "?symbols=AAPL,MSFT[&feed=iex|sip]")
    public JsonNode latestStockQuotes(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.latestStockQuotes(query);
    }

    @GetMapping("/stocks/trades/latest")
    @Operation(summary = "Latest trades", description = "?symbols=AAPL,MSFT[&feed=iex|sip]")
    public JsonNode latestStockTrades(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.latestStockTrades(query);
    }

    @GetMapping("/stocks/bars/latest")
    @Operation(summary = "Latest bars", description = "?symbols=AAPL,MSFT[&feed=iex|sip]")
    public JsonNode latestStockBars(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.latestStockBars(query);
    }

    @GetMapping("/stocks/snapshots")
    @Operation(summary = "Snapshots", description = "?symbols=AAPL,MSFT — latest trade, quote, minute/daily bars")
    public JsonNode stockSnapshots(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.stockSnapshots(query);
    }

    @GetMapping("/stocks/bars")
    @Operation(summary = "Historical bars", description = "?symbols=AAPL&timeframe=1Day&start=...&end=...&limit=...&page_token=...")
    public JsonNode stockBars(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.stockBars(query);
    }

    // ---- Stocks: single symbol ----

    @GetMapping("/stocks/{symbol}/snapshot")
    public JsonNode stockSnapshot(@PathVariable String symbol,
                                  @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.stockSnapshot(symbol, query);
    }

    @GetMapping("/stocks/{symbol}/quotes/latest")
    public JsonNode latestStockQuote(@PathVariable String symbol,
                                     @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.latestStockQuote(symbol, query);
    }

    @GetMapping("/stocks/{symbol}/trades/latest")
    public JsonNode latestStockTrade(@PathVariable String symbol,
                                     @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.latestStockTrade(symbol, query);
    }

    // ---- Crypto ----

    @GetMapping("/crypto/{loc}/quotes/latest")
    @Operation(summary = "Latest crypto quotes", description = "loc=us; ?symbols=BTC/USD,ETH/USD")
    public JsonNode latestCryptoQuotes(@PathVariable String loc,
                                       @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.latestCryptoQuotes(loc, query);
    }

    @GetMapping("/crypto/{loc}/snapshots")
    public JsonNode cryptoSnapshots(@PathVariable String loc,
                                    @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.cryptoSnapshots(loc, query);
    }

    @GetMapping("/crypto/{loc}/bars")
    public JsonNode cryptoBars(@PathVariable String loc,
                               @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return marketData.cryptoBars(loc, query);
    }
}
