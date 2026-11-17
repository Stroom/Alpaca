package com.example.alpaca.events;

import static com.example.alpaca.trading.Json.date;
import static com.example.alpaca.trading.Json.dec;
import static com.example.alpaca.trading.Json.str;
import static com.example.alpaca.trading.Json.ts;

import com.example.alpaca.trading.AccountService;
import com.example.alpaca.trading.Json;
import com.example.alpaca.trading.OrderService;
import com.example.alpaca.trading.SettlementService;
import com.example.alpaca.trading.SettlementService.Execution;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 6: order lifecycle. Handles events from GET /v2/events/trades (new, fill, partial_fill, canceled, ...).
 * Each event carries the full order object, which updates the stored order; the event itself is stored
 * once (keyed by its ULID), and executions start a settlement.
 */
@Component
public class TradeEventHandler implements EventHandler {

    private static final Set<String> EXECUTIONS = Set.of("fill", "partial_fill");

    private final JdbcClient db;
    private final AccountService accounts;
    private final OrderService orders;
    private final SettlementService settlements;
    private final JsonMapper json;

    public TradeEventHandler(JdbcClient db, AccountService accounts, OrderService orders,
                             SettlementService settlements, JsonMapper json) {
        this.db = db;
        this.accounts = accounts;
        this.orders = orders;
        this.settlements = settlements;
        this.json = json;
    }

    @Override
    public String stream() {
        return "trades";
    }

    @Override
    public String path() {
        return "/v2/events/trades";
    }

    @Override
    @Transactional
    public boolean handle(JsonNode e) {
        UUID accountId = Json.uuid(e, "account_id");
        if (!accounts.exists(accountId)) {
            return false; // event for an account this app does not manage
        }
        JsonNode order = e.get("order");
        orders.upsertFromAlpaca(accountId, order);
        UUID orderId = Json.uuid(order, "id");
        String type = str(e, "event");
        OffsetDateTime at = ts(e, "at") != null ? ts(e, "at") : ts(e, "timestamp");

        int inserted = db.sql("""
                INSERT INTO trade_event (event_id, account_id, order_id, event_type, event_at, execution_id,
                                         execution_time, price, qty, position_qty, settle_date, raw)
                VALUES (:id, :account, :order, :type, :at, :exec, :execTime, :price, :qty, :pos, :settle, CAST(:raw AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("id", str(e, "event_id"))
                .param("account", accountId)
                .param("order", orderId)
                .param("type", type)
                .param("at", at)
                .param("exec", str(e, "execution_id"))
                .param("execTime", ts(e, "timestamp"))
                .param("price", dec(e, "price"))
                .param("qty", dec(e, "qty"))
                .param("pos", dec(e, "position_qty"))
                .param("settle", date(e, "settle_date"))
                .param("raw", json.writeValueAsString(e))
                .update();

        if (inserted == 1 && EXECUTIONS.contains(type) && str(e, "execution_id") != null
                && dec(e, "qty") != null && dec(e, "price") != null) {
            OffsetDateTime execTime = ts(e, "timestamp") != null ? ts(e, "timestamp") : at;
            settlements.recordExecution(new Execution(
                    str(e, "event_id"), str(e, "execution_id"), orderId, accountId,
                    str(order, "symbol"), str(order, "side"), dec(e, "qty"), dec(e, "price"),
                    execTime.atZoneSameInstant(SettlementService.MARKET_ZONE).toLocalDate(),
                    date(e, "settle_date")));
        }
        return inserted == 1;
    }
}
