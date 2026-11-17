package com.example.alpaca.web;

import com.example.alpaca.events.AlpacaEventStreams;
import com.example.alpaca.trading.AccountService;
import com.example.alpaca.trading.AccountService.CreateAccountRequest;
import com.example.alpaca.trading.FundingService;
import com.example.alpaca.trading.InstrumentService;
import com.example.alpaca.trading.OrderService;
import com.example.alpaca.trading.OrderService.PlaceOrderRequest;
import com.example.alpaca.trading.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * API behind the web UI: the end-to-end trade flow with everything persisted in Postgres.
 * Reads come from the database; writes go to Alpaca first and are then stored.
 */
@RestController
@RequestMapping("/api/app")
@Tag(name = "Trading app", description = "Account → funding → instrument → quote → order → events → settlement")
public class TradingAppController {

    private final AccountService accounts;
    private final FundingService funding;
    private final InstrumentService instruments;
    private final QuoteService quotes;
    private final OrderService orders;
    private final AlpacaEventStreams streams;
    private final JdbcClient db;

    public TradingAppController(AccountService accounts, FundingService funding, InstrumentService instruments,
                                QuoteService quotes, OrderService orders, AlpacaEventStreams streams, JdbcClient db) {
        this.accounts = accounts;
        this.funding = funding;
        this.instruments = instruments;
        this.quotes = quotes;
        this.orders = orders;
        this.streams = streams;
        this.db = db;
    }

    // ---- Accounts ----

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts() {
        return accounts.list();
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an Alpaca account (POST /v1/accounts) and store it")
    public Map<String, Object> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return accounts.create(request);
    }

    public record ImportAccountRequest(@NotNull UUID accountId) {
    }

    @PostMapping("/accounts/import")
    @Operation(summary = "Import an existing Alpaca account into the database")
    public Map<String, Object> importAccount(@Valid @RequestBody ImportAccountRequest request) {
        return accounts.importAccount(request.accountId());
    }

    @GetMapping("/accounts/{accountId}")
    @Operation(summary = "Refresh account from Alpaca and return it with live balances")
    public Map<String, Object> account(@PathVariable UUID accountId) {
        if (!accounts.exists(accountId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown account " + accountId);
        }
        Map<String, Object> result = new LinkedHashMap<>(accounts.importAccount(accountId));
        try {
            result.put("trading", accounts.tradingAccount(accountId));
        } catch (RuntimeException e) {
            result.put("trading", null); // not available until the account is approved
        }
        return result;
    }

    public record FundRequest(@NotNull BigDecimal amount) {
    }

    @PostMapping("/accounts/{accountId}/funding")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Fund the account (sandbox: simulated incoming wire)")
    public Map<String, Object> fund(@PathVariable UUID accountId, @Valid @RequestBody FundRequest request) {
        return funding.fund(accountId, request.amount());
    }

    @GetMapping("/accounts/{accountId}/funding")
    public List<Map<String, Object>> fundings(@PathVariable UUID accountId) {
        return funding.list(accountId);
    }

    @GetMapping("/accounts/{accountId}/activities")
    @Operation(summary = "Account ledger received from the activities event stream")
    public List<Map<String, Object>> activities(@PathVariable UUID accountId) {
        return db.sql("""
                SELECT event_id, activity_type, activity_subtype, order_id, symbol, qty, price, net_amount, currency,
                       status, executed_at, to_char(settle_date, 'YYYY-MM-DD') AS settle_date, received_at
                  FROM account_activity WHERE account_id = :a ORDER BY executed_at DESC NULLS LAST LIMIT 200
                """).param("a", accountId).query().listOfRows();
    }

    // ---- Instruments & prices ----

    @GetMapping("/instruments")
    @Operation(summary = "Search instruments in the local database by symbol or name")
    public Map<String, Object> searchInstruments(@RequestParam(defaultValue = "") String q,
                                                 @RequestParam(defaultValue = "25") int limit) {
        return Map.of("total", instruments.count(), "results", instruments.search(q, limit));
    }

    @PostMapping("/instruments/sync")
    @Operation(summary = "Reload active US equities from Alpaca (GET /v1/assets)")
    public Map<String, Object> syncInstruments() {
        return Map.of("synced", instruments.sync());
    }

    @GetMapping("/instruments/{symbol}")
    public Map<String, Object> instrument(@PathVariable String symbol) {
        return instruments.get(symbol);
    }

    @PostMapping("/instruments/{symbol}/quote")
    @Operation(summary = "Fetch a snapshot from the Market Data API and store it")
    public Map<String, Object> quote(@PathVariable String symbol) {
        return quotes.latest(symbol);
    }

    @GetMapping("/instruments/{symbol}/quotes")
    @Operation(summary = "Stored quote history for a symbol")
    public List<Map<String, Object>> quoteHistory(@PathVariable String symbol,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return quotes.history(symbol, Math.min(limit, 200));
    }

    // ---- Orders ----

    @PostMapping("/accounts/{accountId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Capture a quote, submit the order to Alpaca and store it")
    public Map<String, Object> placeOrder(@PathVariable UUID accountId, @Valid @RequestBody PlaceOrderRequest request) {
        return orders.place(accountId, request);
    }

    @GetMapping("/accounts/{accountId}/orders")
    @Operation(summary = "Stored orders with lifecycle events and executions (incl. settlement status)")
    public List<Map<String, Object>> orders(@PathVariable UUID accountId, @RequestParam(defaultValue = "50") int limit) {
        return orders.listWithExecutions(accountId, Math.min(limit, 200));
    }

    @DeleteMapping("/accounts/{accountId}/orders/{orderId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request cancellation; the result arrives as a trade event")
    public void cancelOrder(@PathVariable UUID accountId, @PathVariable UUID orderId) {
        orders.cancel(accountId, orderId);
    }

    // ---- Diagnostics ----

    @GetMapping("/streams")
    @Operation(summary = "State of the background Alpaca event streams")
    public List<AlpacaEventStreams.StreamStatus> streams() {
        return streams.statuses();
    }
}
