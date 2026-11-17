package com.example.alpaca.trading;

import static com.example.alpaca.trading.Json.dec;
import static com.example.alpaca.trading.Json.ts;

import com.example.alpaca.config.AlpacaProperties;
import com.example.alpaca.marketdata.MarketDataClient;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 4: prices. Every quote request calls GET /v2/stocks/{symbol}/snapshot (latest quote, latest trade,
 * minute/daily bars) and stores the result, so each order keeps a reference to the price seen when it was placed.
 */
@Service
public class QuoteService {

    private static final String COLUMNS = """
            id, symbol, feed, bid_price, bid_size, ask_price, ask_size, last_price, quote_time, trade_time,
            daily_open, daily_high, daily_low, daily_close, daily_volume, prev_close, fetched_at""";

    private final JdbcClient db;
    private final MarketDataClient marketData;
    private final AlpacaProperties properties;
    private final JsonMapper json;

    public QuoteService(JdbcClient db, MarketDataClient marketData, AlpacaProperties properties, JsonMapper json) {
        this.db = db;
        this.marketData = marketData;
        this.properties = properties;
        this.json = json;
    }

    public Map<String, Object> latest(String symbol) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("feed", properties.marketDataFeed());
        JsonNode snap = marketData.stockSnapshot(symbol.toUpperCase(), query);

        JsonNode quote = snap.get("latestQuote");
        JsonNode trade = snap.get("latestTrade");
        JsonNode daily = snap.get("dailyBar");
        JsonNode prev = snap.get("prevDailyBar");

        Long id = db.sql("""
                INSERT INTO price_quote (symbol, feed, bid_price, bid_size, ask_price, ask_size, last_price,
                                         quote_time, trade_time, daily_open, daily_high, daily_low, daily_close,
                                         daily_volume, prev_close, raw)
                VALUES (:symbol, :feed, :bp, :bs, :ap, :as, :last, :qt, :tt, :o, :h, :l, :c, :v, :prev, CAST(:raw AS jsonb))
                RETURNING id
                """)
                .param("symbol", symbol.toUpperCase())
                .param("feed", properties.marketDataFeed())
                .param("bp", dec(quote, "bp"))
                .param("bs", dec(quote, "bs"))
                .param("ap", dec(quote, "ap"))
                .param("as", dec(quote, "as"))
                .param("last", dec(trade, "p"))
                .param("qt", ts(quote, "t"))
                .param("tt", ts(trade, "t"))
                .param("o", dec(daily, "o"))
                .param("h", dec(daily, "h"))
                .param("l", dec(daily, "l"))
                .param("c", dec(daily, "c"))
                .param("v", dec(daily, "v"))
                .param("prev", dec(prev, "c"))
                .param("raw", json.writeValueAsString(snap))
                .query(Long.class).single();
        return get(id);
    }

    public Map<String, Object> get(long id) {
        return db.sql("SELECT " + COLUMNS + " FROM price_quote WHERE id = :id").param("id", id).query().singleRow();
    }

    public List<Map<String, Object>> history(String symbol, int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM price_quote WHERE symbol = :s ORDER BY fetched_at DESC LIMIT :limit")
                .param("s", symbol.toUpperCase()).param("limit", limit).query().listOfRows();
    }
}
