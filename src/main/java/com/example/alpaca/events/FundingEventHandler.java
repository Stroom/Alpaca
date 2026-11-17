package com.example.alpaca.events;

import static com.example.alpaca.trading.Json.str;
import static com.example.alpaca.trading.Json.ts;

import com.example.alpaca.trading.AccountService;
import com.example.alpaca.trading.Json;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Handles GET /v2/events/funding/status: status changes of transfers, bank relationships and wire banks. */
@Component
public class FundingEventHandler implements EventHandler {

    private final JdbcClient db;
    private final AccountService accounts;
    private final JsonMapper json;

    public FundingEventHandler(JdbcClient db, AccountService accounts, JsonMapper json) {
        this.db = db;
        this.accounts = accounts;
        this.json = json;
    }

    @Override
    public String stream() {
        return "funding";
    }

    @Override
    public String path() {
        return "/v2/events/funding/status";
    }

    @Override
    public boolean handle(JsonNode e) {
        UUID accountId = Json.uuid(e, "account_id");
        if (!accounts.exists(accountId)) {
            return false;
        }
        int inserted = db.sql("""
                INSERT INTO funding_event (event_id, account_id, entity_type, entity_id, status_from, status_to,
                                           reason, event_at, raw)
                VALUES (:id, :account, :entityType, :entityId, :from, :to, :reason, :at, CAST(:raw AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("id", str(e, "event_id"))
                .param("account", accountId)
                .param("entityType", str(e, "entity_type"))
                .param("entityId", Json.uuid(e, "entity_id"))
                .param("from", str(e, "status_from"))
                .param("to", str(e, "status_to"))
                .param("reason", str(e, "reason"))
                .param("at", ts(e, "at"))
                .param("raw", json.writeValueAsString(e))
                .update();
        // Keep simulated-wire transfers in sync when Alpaca reports on them by id
        db.sql("UPDATE funding_transfer SET status = :to, updated_at = now() WHERE id = :entityId AND status <> 'EXECUTED'")
                .param("to", str(e, "status_to"))
                .param("entityId", Json.uuid(e, "entity_id"))
                .update();
        return inserted == 1;
    }
}
