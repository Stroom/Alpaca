package com.example.alpaca.trading;

import static com.example.alpaca.trading.Json.str;
import static com.example.alpaca.trading.Json.ts;

import com.example.alpaca.broker.BrokerClient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 1 of the trade flow: create (or import) Alpaca brokerage accounts and keep a local copy.
 * Alpaca endpoints: POST /v1/accounts, GET /v1/accounts/{id}, GET /v1/trading/accounts/{id}/account.
 */
@Service
public class AccountService {

    private static final String COLUMNS =
            "id, account_number, status, given_name, family_name, email, currency, alpaca_created_at, created_at, updated_at";

    private final JdbcClient db;
    private final BrokerClient broker;
    private final JsonMapper json;

    public AccountService(JdbcClient db, BrokerClient broker, JsonMapper json) {
        this.db = db;
        this.broker = broker;
        this.json = json;
    }

    /** KYC data for a new account. Fields not asked for in the UI get sandbox-friendly defaults. */
    public record CreateAccountRequest(
            @NotBlank String givenName,
            @NotBlank String familyName,
            @NotBlank @Email String email,
            String phone,
            String street,
            String city,
            String state,
            String postalCode,
            String dateOfBirth,
            String taxId,
            String ipAddress) {
    }

    public Map<String, Object> create(CreateAccountRequest r) {
        String now = Instant.now().toString();
        String ip = orDefault(r.ipAddress(), "127.0.0.1");

        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("email_address", r.email());
        if (r.phone() != null && !r.phone().isBlank()) {
            contact.put("phone_number", r.phone());
        }
        contact.put("street_address", List.of(orDefault(r.street(), "20 N San Mateo Dr")));
        contact.put("city", orDefault(r.city(), "San Mateo"));
        contact.put("state", orDefault(r.state(), "CA"));
        contact.put("postal_code", orDefault(r.postalCode(), "94401"));
        contact.put("country", "USA");

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("given_name", r.givenName());
        identity.put("family_name", r.familyName());
        identity.put("date_of_birth", orDefault(r.dateOfBirth(), "1990-01-01"));
        if (r.taxId() != null && !r.taxId().isBlank()) {
            identity.put("tax_id", r.taxId());
            identity.put("tax_id_type", "USA_SSN");
        }
        identity.put("country_of_citizenship", "USA");
        identity.put("country_of_birth", "USA");
        identity.put("country_of_tax_residence", "USA");
        identity.put("funding_source", List.of("employment_income"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contact", contact);
        body.put("identity", identity);
        body.put("disclosures", Map.of(
                "is_control_person", false,
                "is_affiliated_exchange_or_finra", false,
                "is_politically_exposed", false,
                "immediate_family_exposed", false));
        body.put("agreements", List.of(
                Map.of("agreement", "customer_agreement", "signed_at", now, "ip_address", ip),
                Map.of("agreement", "account_agreement", "signed_at", now, "ip_address", ip),
                Map.of("agreement", "margin_agreement", "signed_at", now, "ip_address", ip)));
        body.put("enabled_assets", List.of("us_equity"));

        JsonNode created = broker.createAccount(json.valueToTree(body));
        upsert(created);
        return get(Json.uuid(created, "id")).orElseThrow();
    }

    /** Imports an existing Alpaca account (or refreshes the stored copy). */
    public Map<String, Object> importAccount(UUID accountId) {
        upsert(broker.getAccount(accountId.toString()));
        return get(accountId).orElseThrow();
    }

    public List<Map<String, Object>> list() {
        return db.sql("SELECT " + COLUMNS + " FROM account ORDER BY created_at DESC").query().listOfRows();
    }

    public Optional<Map<String, Object>> get(UUID id) {
        return db.sql("SELECT " + COLUMNS + " FROM account WHERE id = :id").param("id", id).query().listOfRows()
                .stream().findFirst();
    }

    public boolean exists(UUID id) {
        return id != null && db.sql("SELECT count(*) FROM account WHERE id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }

    public String accountNumber(UUID id) {
        return db.sql("SELECT account_number FROM account WHERE id = :id").param("id", id)
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown account " + id));
    }

    /** Live balances (cash, buying power, equity) straight from Alpaca. */
    public JsonNode tradingAccount(UUID id) {
        return broker.getTradingAccount(id.toString());
    }

    void upsert(JsonNode a) {
        JsonNode identity = a.get("identity");
        JsonNode contact = a.get("contact");
        db.sql("""
                INSERT INTO account (id, account_number, status, given_name, family_name, email, currency,
                                     alpaca_created_at, raw)
                VALUES (:id, :number, :status, :given, :family, :email, :currency, :created, CAST(:raw AS jsonb))
                ON CONFLICT (id) DO UPDATE SET
                    account_number = EXCLUDED.account_number,
                    status = EXCLUDED.status,
                    given_name = COALESCE(EXCLUDED.given_name, account.given_name),
                    family_name = COALESCE(EXCLUDED.family_name, account.family_name),
                    email = COALESCE(EXCLUDED.email, account.email),
                    currency = EXCLUDED.currency,
                    raw = EXCLUDED.raw,
                    updated_at = now()
                """)
                .param("id", Json.uuid(a, "id"))
                .param("number", str(a, "account_number"))
                .param("status", str(a, "status"))
                .param("given", str(identity, "given_name"))
                .param("family", str(identity, "family_name"))
                .param("email", str(contact, "email_address"))
                .param("currency", str(a, "currency"))
                .param("created", ts(a, "created_at"))
                .param("raw", json.writeValueAsString(a))
                .update();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
