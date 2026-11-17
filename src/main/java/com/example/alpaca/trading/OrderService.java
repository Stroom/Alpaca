package com.example.alpaca.trading;

import static com.example.alpaca.trading.Json.dec;
import static com.example.alpaca.trading.Json.str;
import static com.example.alpaca.trading.Json.ts;

import com.example.alpaca.broker.BrokerClient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 5: place orders. Looks up the instrument, captures a fresh quote, submits the order with our own
 * client_order_id (POST /v1/trading/accounts/{id}/orders) and stores it. All later state changes arrive
 * through the trade events stream (see TradeEventHandler).
 */
@Service
public class OrderService {

    private final JdbcClient db;
    private final BrokerClient broker;
    private final AccountService accounts;
    private final InstrumentService instruments;
    private final QuoteService quotes;
    private final JsonMapper json;

    public OrderService(JdbcClient db, BrokerClient broker, AccountService accounts, InstrumentService instruments,
                        QuoteService quotes, JsonMapper json) {
        this.db = db;
        this.broker = broker;
        this.accounts = accounts;
        this.instruments = instruments;
        this.quotes = quotes;
        this.json = json;
    }

    public record PlaceOrderRequest(
            @NotBlank String symbol,
            @NotBlank @Pattern(regexp = "buy|sell") String side,
            @NotBlank @Pattern(regexp = "market|limit|stop|stop_limit") String type,
            @Pattern(regexp = "day|gtc|opg|cls|ioc|fok") String timeInForce,
            BigDecimal qty,
            BigDecimal notional,
            BigDecimal limitPrice,
            BigDecimal stopPrice) {
    }

    public Map<String, Object> place(UUID accountId, PlaceOrderRequest r) {
        if (!accounts.exists(accountId)) {
            throw new IllegalArgumentException("Unknown account " + accountId);
        }
        if ((r.qty() == null) == (r.notional() == null)) {
            throw new IllegalArgumentException("Specify exactly one of qty or notional");
        }
        Map<String, Object> instrument = instruments.get(r.symbol());
        if (!Boolean.TRUE.equals(instrument.get("tradable"))) {
            throw new IllegalArgumentException(r.symbol() + " is not tradable");
        }
        String symbol = (String) instrument.get("symbol");
        Map<String, Object> quote = quotes.latest(symbol);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("side", r.side());
        body.put("type", r.type());
        body.put("time_in_force", r.timeInForce() == null ? "day" : r.timeInForce());
        if (r.qty() != null) {
            body.put("qty", r.qty().toPlainString());
        } else {
            body.put("notional", r.notional().toPlainString());
        }
        if (r.limitPrice() != null) {
            body.put("limit_price", r.limitPrice().toPlainString());
        }
        if (r.stopPrice() != null) {
            body.put("stop_price", r.stopPrice().toPlainString());
        }
        body.put("client_order_id", "at-" + UUID.randomUUID());

        JsonNode order = broker.createOrder(accountId.toString(), json.valueToTree(body));
        upsertFromAlpaca(accountId, order);
        UUID orderId = Json.uuid(order, "id");
        db.sql("UPDATE trade_order SET quote_id = :quote WHERE id = :id AND quote_id IS NULL")
                .param("quote", quote.get("id")).param("id", orderId).update();
        return findOrder(orderId);
    }

    public void cancel(UUID accountId, UUID orderId) {
        broker.cancelOrder(accountId.toString(), orderId.toString());
    }

    /**
     * Inserts or updates the stored order from an Alpaca order object (REST response or event payload).
     * Older snapshots (by order.updated_at) never overwrite newer ones, so replayed or out-of-order events are harmless.
     */
    public void upsertFromAlpaca(UUID accountId, JsonNode o) {
        db.sql("""
                INSERT INTO trade_order (id, client_order_id, account_id, symbol, asset_class, side, order_type,
                        time_in_force, qty, notional, limit_price, stop_price, status, filled_qty, filled_avg_price,
                        submitted_at, filled_at, canceled_at, alpaca_updated_at, raw)
                VALUES (:id, :cid, :account, :symbol, :assetClass, :side, :type, :tif, :qty, :notional, :limit, :stop,
                        :status, :filledQty, :avgPrice, :submitted, :filled, :canceled, :updated, CAST(:raw AS jsonb))
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    qty = COALESCE(EXCLUDED.qty, trade_order.qty),
                    limit_price = EXCLUDED.limit_price,
                    stop_price = EXCLUDED.stop_price,
                    filled_qty = EXCLUDED.filled_qty,
                    filled_avg_price = EXCLUDED.filled_avg_price,
                    filled_at = EXCLUDED.filled_at,
                    canceled_at = EXCLUDED.canceled_at,
                    alpaca_updated_at = EXCLUDED.alpaca_updated_at,
                    raw = EXCLUDED.raw,
                    updated_at = now()
                WHERE trade_order.alpaca_updated_at IS NULL
                   OR EXCLUDED.alpaca_updated_at IS NULL
                   OR EXCLUDED.alpaca_updated_at >= trade_order.alpaca_updated_at
                """)
                .param("id", Json.uuid(o, "id"))
                .param("cid", str(o, "client_order_id") == null ? str(o, "id") : str(o, "client_order_id"))
                .param("account", accountId)
                .param("symbol", str(o, "symbol"))
                .param("assetClass", str(o, "asset_class"))
                .param("side", str(o, "side"))
                .param("type", str(o, "type") == null ? str(o, "order_type") : str(o, "type"))
                .param("tif", str(o, "time_in_force"))
                .param("qty", dec(o, "qty"))
                .param("notional", dec(o, "notional"))
                .param("limit", dec(o, "limit_price"))
                .param("stop", dec(o, "stop_price"))
                .param("status", str(o, "status"))
                .param("filledQty", dec(o, "filled_qty"))
                .param("avgPrice", dec(o, "filled_avg_price"))
                .param("submitted", ts(o, "submitted_at"))
                .param("filled", ts(o, "filled_at"))
                .param("canceled", ts(o, "canceled_at"))
                .param("updated", ts(o, "updated_at"))
                .param("raw", json.writeValueAsString(o))
                .update();
    }

    private static final String ORDER_SELECT = """
            SELECT o.id, o.client_order_id, o.account_id, o.symbol, o.side, o.order_type, o.time_in_force, o.qty,
                   o.notional, o.limit_price, o.stop_price, o.status, o.filled_qty, o.filled_avg_price,
                   o.submitted_at, o.filled_at, o.canceled_at, o.created_at, o.updated_at,
                   q.id AS quote_id, q.bid_price AS quote_bid, q.ask_price AS quote_ask, q.last_price AS quote_last,
                   q.fetched_at AS quote_fetched_at
              FROM trade_order o LEFT JOIN price_quote q ON q.id = o.quote_id
            """;

    public Map<String, Object> findOrder(UUID orderId) {
        return db.sql(ORDER_SELECT + " WHERE o.id = :id").param("id", orderId).query().singleRow();
    }

    /** Orders of an account with their lifecycle events and executions (fills + settlement state). */
    public List<Map<String, Object>> listWithExecutions(UUID accountId, int limit) {
        List<Map<String, Object>> orders = db.sql(ORDER_SELECT + " WHERE o.account_id = :account ORDER BY o.created_at DESC LIMIT :limit")
                .param("account", accountId).param("limit", limit).query().listOfRows();
        if (orders.isEmpty()) {
            return orders;
        }
        List<UUID> ids = orders.stream().map(o -> (UUID) o.get("id")).toList();

        Map<Object, List<Map<String, Object>>> events = db.sql("""
                SELECT event_id, order_id, event_type, event_at, execution_id, execution_time, price, qty,
                       position_qty, to_char(settle_date, 'YYYY-MM-DD') AS settle_date, received_at
                  FROM trade_event WHERE order_id IN (:ids) ORDER BY event_at, event_id
                """).param("ids", ids).query().listOfRows().stream()
                .collect(Collectors.groupingBy(e -> e.get("order_id")));

        Map<Object, List<Map<String, Object>>> executions = db.sql("""
                SELECT s.execution_id, s.order_id, s.trade_event_id, s.side, s.qty, s.price, s.gross_amount,
                       to_char(s.trade_date, 'YYYY-MM-DD') AS trade_date, to_char(s.settle_date, 'YYYY-MM-DD') AS settle_date, s.status AS settlement_status, s.activity_event_id,
                       s.activity_status, s.settled_at, e.execution_time
                  FROM settlement s JOIN trade_event e ON e.event_id = s.trade_event_id
                 WHERE s.order_id IN (:ids) ORDER BY e.execution_time, s.id
                """).param("ids", ids).query().listOfRows().stream()
                .collect(Collectors.groupingBy(e -> e.get("order_id")));

        List<Map<String, Object>> result = new ArrayList<>(orders.size());
        for (Map<String, Object> o : orders) {
            Map<String, Object> copy = new LinkedHashMap<>(o);
            copy.put("events", events.getOrDefault(o.get("id"), List.of()));
            copy.put("executions", executions.getOrDefault(o.get("id"), List.of()));
            result.add(copy);
        }
        return result;
    }
}
