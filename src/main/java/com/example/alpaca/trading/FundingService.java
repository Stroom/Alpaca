package com.example.alpaca.trading;

import static com.example.alpaca.trading.Json.str;

import com.example.alpaca.broker.BrokerClient;
import com.example.alpaca.config.AlpacaProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 2: fund the account so it has buying power. In the sandbox this uses a simulated incoming wire
 * (POST /v1/testing/incoming_wires). The deposit is confirmed when a CSD activity arrives on the
 * activities event stream.
 */
@Service
public class FundingService {

    private final JdbcClient db;
    private final BrokerClient broker;
    private final AccountService accounts;
    private final AlpacaProperties properties;
    private final JsonMapper json;

    public FundingService(JdbcClient db, BrokerClient broker, AccountService accounts,
                          AlpacaProperties properties, JsonMapper json) {
        this.db = db;
        this.broker = broker;
        this.accounts = accounts;
        this.properties = properties;
        this.json = json;
    }

    public Map<String, Object> fund(UUID accountId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }
        String correspondent = properties.correspondent();
        if (correspondent == null || correspondent.isBlank()) {
            throw new IllegalStateException("Set ALPACA_CORRESPONDENT (your 4-character correspondent code) to fund accounts");
        }
        String accountNumber = accounts.accountNumber(accountId);
        if (accountNumber == null) {
            throw new IllegalStateException("Account has no account number yet; wait until it is approved");
        }

        JsonNode wire = broker.createIncomingWire(json.valueToTree(Map.of(
                "amount", amount.toPlainString(),
                "currency", "USD",
                "wire_instructions", "FFC " + correspondent + "-" + accountNumber)));

        UUID id = Json.uuid(wire, "id");
        db.sql("""
                INSERT INTO funding_transfer (id, account_id, method, amount, currency, status, raw)
                VALUES (:id, :account, 'INCOMING_WIRE', :amount, :currency, :status, CAST(:raw AS jsonb))
                """)
                .param("id", id)
                .param("account", accountId)
                .param("amount", amount)
                .param("currency", str(wire, "currency") == null ? "USD" : str(wire, "currency"))
                .param("status", str(wire, "status") == null ? "QUEUED" : str(wire, "status"))
                .param("raw", json.writeValueAsString(wire))
                .update();
        return db.sql("SELECT id, account_id, method, amount, currency, status, created_at, updated_at FROM funding_transfer WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    public List<Map<String, Object>> list(UUID accountId) {
        return db.sql("""
                SELECT id, account_id, method, amount, currency, status, activity_event_id, created_at, updated_at
                FROM funding_transfer WHERE account_id = :account ORDER BY created_at DESC
                """).param("account", accountId).query().listOfRows();
    }

    /** Called for CSD (cash deposit) activities: marks the oldest open transfer with the same amount as executed. */
    public void onCashDeposit(UUID accountId, BigDecimal amount, String activityEventId) {
        if (amount == null) {
            return;
        }
        db.sql("""
                UPDATE funding_transfer SET status = 'EXECUTED', activity_event_id = :event, updated_at = now()
                WHERE id = (SELECT id FROM funding_transfer
                            WHERE account_id = :account AND amount = :amount AND activity_event_id IS NULL
                            ORDER BY created_at LIMIT 1)
                """)
                .param("event", activityEventId)
                .param("account", accountId)
                .param("amount", amount)
                .update();
    }
}
