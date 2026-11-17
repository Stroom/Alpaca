package com.example.alpaca.trading;

import static com.example.alpaca.trading.Json.str;

import com.example.alpaca.broker.BrokerClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 3: find instruments. The tradable asset universe (GET /v1/assets) is cached in the instrument table
 * so searching is a local query; it is loaded on first start and can be re-synced on demand.
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);
    private static final String COLUMNS = "id, symbol, name, exchange, asset_class, status, tradable, fractionable, shortable, updated_at";
    private static final String UPSERT = """
            INSERT INTO instrument (id, symbol, name, exchange, asset_class, status, tradable, fractionable, shortable, raw)
            VALUES (:id, :symbol, :name, :exchange, :assetClass, :status, :tradable, :fractionable, :shortable, CAST(:raw AS jsonb))
            ON CONFLICT (id) DO UPDATE SET
                symbol = EXCLUDED.symbol, name = EXCLUDED.name, exchange = EXCLUDED.exchange,
                asset_class = EXCLUDED.asset_class, status = EXCLUDED.status, tradable = EXCLUDED.tradable,
                fractionable = EXCLUDED.fractionable, shortable = EXCLUDED.shortable, raw = EXCLUDED.raw,
                updated_at = now()
            """;

    private final JdbcClient db;
    private final NamedParameterJdbcTemplate jdbc;
    private final BrokerClient broker;
    private final JsonMapper json;
    private final AtomicBoolean syncing = new AtomicBoolean();

    public InstrumentService(JdbcClient db, NamedParameterJdbcTemplate jdbc, BrokerClient broker, JsonMapper json) {
        this.db = db;
        this.jdbc = jdbc;
        this.broker = broker;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    void loadOnFirstStart() {
        if (count() == 0) {
            Thread.ofVirtual().name("instrument-sync").start(() -> {
                try {
                    sync();
                } catch (Exception e) {
                    log.warn("Initial instrument sync failed: {}", e.toString());
                }
            });
        }
    }

    /** Loads all active US equities from Alpaca into the instrument table. Returns the number stored. */
    public int sync() {
        if (!syncing.compareAndSet(false, true)) {
            throw new IllegalStateException("Instrument sync already running");
        }
        try {
            MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
            query.add("status", "active");
            query.add("asset_class", "us_equity");
            JsonNode assets = broker.listAssets(query);

            List<SqlParameterSource> batch = new ArrayList<>();
            for (JsonNode a : assets) {
                batch.add(params(a));
            }
            jdbc.batchUpdate(UPSERT, batch.toArray(SqlParameterSource[]::new));
            log.info("Synced {} instruments from Alpaca", batch.size());
            return batch.size();
        } finally {
            syncing.set(false);
        }
    }

    public long count() {
        return db.sql("SELECT count(*) FROM instrument").query(Long.class).single();
    }

    public List<Map<String, Object>> search(String q, int limit) {
        String term = q == null ? "" : q.trim().replaceAll("[%_\\\\]", "");
        if (term.isEmpty()) {
            return List.of();
        }
        return db.sql("SELECT " + COLUMNS + """
                 FROM instrument
                WHERE status = 'active' AND (symbol ILIKE :term || '%' OR name ILIKE '%' || :term || '%')
                ORDER BY (upper(symbol) = upper(:term)) DESC, (symbol ILIKE :term || '%') DESC,
                         tradable DESC, length(symbol), symbol
                LIMIT :limit
                """)
                .param("term", term)
                .param("limit", Math.min(Math.max(limit, 1), 100))
                .query().listOfRows();
    }

    /** Looks up a symbol locally, falling back to GET /v1/assets/{symbol} for anything not cached yet. */
    public Map<String, Object> get(String symbol) {
        Optional<Map<String, Object>> local = db.sql("SELECT " + COLUMNS + " FROM instrument WHERE upper(symbol) = upper(:s)")
                .param("s", symbol).query().listOfRows().stream().findFirst();
        if (local.isPresent()) {
            return local.get();
        }
        JsonNode asset = broker.getAsset(symbol);
        jdbc.update(UPSERT, params(asset));
        return db.sql("SELECT " + COLUMNS + " FROM instrument WHERE id = :id")
                .param("id", Json.uuid(asset, "id")).query().singleRow();
    }

    private MapSqlParameterSource params(JsonNode a) {
        return new MapSqlParameterSource()
                .addValue("id", Json.uuid(a, "id"))
                .addValue("symbol", str(a, "symbol"))
                .addValue("name", str(a, "name"))
                .addValue("exchange", str(a, "exchange"))
                .addValue("assetClass", str(a, "class"))
                .addValue("status", str(a, "status"))
                .addValue("tradable", Json.bool(a, "tradable"))
                .addValue("fractionable", Json.bool(a, "fractionable"))
                .addValue("shortable", Json.bool(a, "shortable"))
                .addValue("raw", json.writeValueAsString(a));
    }
}
