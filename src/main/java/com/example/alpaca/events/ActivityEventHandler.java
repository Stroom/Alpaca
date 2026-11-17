package com.example.alpaca.events;

import static com.example.alpaca.trading.Json.date;
import static com.example.alpaca.trading.Json.dec;
import static com.example.alpaca.trading.Json.str;
import static com.example.alpaca.trading.Json.ts;

import com.example.alpaca.trading.AccountService;
import com.example.alpaca.trading.FundingService;
import com.example.alpaca.trading.Json;
import com.example.alpaca.trading.SettlementService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles GET /v2beta1/events/activities: the account ledger as booked by Alpaca's back office.
 * FILL activities confirm executions (with settle_date), CSD activities confirm cash deposits.
 */
@Component
public class ActivityEventHandler implements EventHandler {

    private final JdbcClient db;
    private final AccountService accounts;
    private final SettlementService settlements;
    private final FundingService funding;
    private final JsonMapper json;

    public ActivityEventHandler(JdbcClient db, AccountService accounts, SettlementService settlements,
                                FundingService funding, JsonMapper json) {
        this.db = db;
        this.accounts = accounts;
        this.settlements = settlements;
        this.funding = funding;
        this.json = json;
    }

    @Override
    public String stream() {
        return "activities";
    }

    @Override
    public String path() {
        return "/v2beta1/events/activities";
    }

    @Override
    @Transactional
    public boolean handle(JsonNode e) {
        UUID accountId = Json.uuid(e, "account_id");
        if (!accounts.exists(accountId)) {
            return false;
        }
        JsonNode details = e.get("details");
        JsonNode asset = e.get("asset");
        String type = str(e, "activity_type");
        UUID orderId = Json.uuid(details, "order_id");
        String symbol = str(asset, "symbol") != null ? str(asset, "symbol") : str(details, "symbol");
        String eventId = str(e, "event_id");

        int inserted = db.sql("""
                INSERT INTO account_activity (event_id, account_id, activity_type, activity_subtype, order_id, symbol,
                                              qty, price, net_amount, currency, status, executed_at, settle_date, raw)
                VALUES (:id, :account, :type, :subtype, :order, :symbol, :qty, :price, :net, :currency, :status,
                        :executed, :settle, CAST(:raw AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("id", eventId)
                .param("account", accountId)
                .param("type", type)
                .param("subtype", str(e, "activity_subtype"))
                .param("order", orderId)
                .param("symbol", symbol)
                .param("qty", dec(e, "qty"))
                .param("price", dec(e, "price"))
                .param("net", dec(e, "net_amount"))
                .param("currency", str(e, "currency"))
                .param("status", str(e, "status"))
                .param("executed", ts(e, "executed_at"))
                .param("settle", date(e, "settle_date"))
                .param("raw", json.writeValueAsString(e))
                .update();

        if (inserted == 1) {
            if (("FILL".equals(type) || "TRD".equals(type)) && orderId != null) {
                settlements.linkFillActivities(orderId);
            } else if ("CSD".equals(type)) {
                funding.onCashDeposit(accountId, dec(e, "net_amount"), eventId);
            }
        }
        return inserted == 1;
    }
}
