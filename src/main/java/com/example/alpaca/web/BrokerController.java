package com.example.alpaca.web;

import com.example.alpaca.broker.BrokerClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/broker")
@Tag(name = "Broker", description = "Accounts, orders and positions via the Alpaca Broker API")
public class BrokerController {

    private final BrokerClient broker;

    public BrokerController(BrokerClient broker) {
        this.broker = broker;
    }

    // ---- Accounts ----

    @GetMapping("/accounts")
    @Operation(summary = "List accounts", description = "Query params forwarded (query, created_after, status, ...)")
    public JsonNode listAccounts(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return broker.listAccounts(query);
    }

    @GetMapping("/accounts/{accountId}")
    public JsonNode getAccount(@PathVariable String accountId) {
        return broker.getAccount(accountId);
    }

    @GetMapping("/accounts/{accountId}/trading")
    @Operation(summary = "Trading account details (buying power, cash, equity)")
    public JsonNode getTradingAccount(@PathVariable String accountId) {
        return broker.getTradingAccount(accountId);
    }

    // ---- Orders ----

    @PostMapping("/accounts/{accountId}/orders")
    @Operation(summary = "Place an order",
            description = "Body forwarded as-is, e.g. {\"symbol\":\"AAPL\",\"qty\":\"1\",\"side\":\"buy\",\"type\":\"market\",\"time_in_force\":\"day\"}")
    public JsonNode createOrder(@PathVariable String accountId, @RequestBody JsonNode order) {
        return broker.createOrder(accountId, order);
    }

    @GetMapping("/accounts/{accountId}/orders")
    @Operation(summary = "List orders", description = "Query params forwarded (status=open|closed|all, limit, after, until, symbols, nested, ...)")
    public JsonNode listOrders(@PathVariable String accountId,
                               @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return broker.listOrders(accountId, query);
    }

    @GetMapping("/accounts/{accountId}/orders/{orderId}")
    public JsonNode getOrder(@PathVariable String accountId, @PathVariable String orderId) {
        return broker.getOrder(accountId, orderId);
    }

    @GetMapping("/accounts/{accountId}/orders/by-client-order-id/{clientOrderId}")
    public JsonNode getOrderByClientOrderId(@PathVariable String accountId, @PathVariable String clientOrderId) {
        return broker.getOrderByClientOrderId(accountId, clientOrderId);
    }

    @PatchMapping("/accounts/{accountId}/orders/{orderId}")
    @Operation(summary = "Replace an order", description = "Body forwarded as-is (qty, limit_price, stop_price, time_in_force, ...)")
    public JsonNode replaceOrder(@PathVariable String accountId, @PathVariable String orderId,
                                 @RequestBody JsonNode changes) {
        return broker.replaceOrder(accountId, orderId, changes);
    }

    @DeleteMapping("/accounts/{accountId}/orders/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable String accountId, @PathVariable String orderId) {
        broker.cancelOrder(accountId, orderId);
    }

    @DeleteMapping("/accounts/{accountId}/orders")
    @Operation(summary = "Cancel all open orders")
    public JsonNode cancelAllOrders(@PathVariable String accountId) {
        return broker.cancelAllOrders(accountId);
    }

    // ---- Positions ----

    @GetMapping("/accounts/{accountId}/positions")
    public JsonNode listPositions(@PathVariable String accountId) {
        return broker.listPositions(accountId);
    }

    @GetMapping("/accounts/{accountId}/positions/{symbol}")
    public JsonNode getPosition(@PathVariable String accountId, @PathVariable String symbol) {
        return broker.getPosition(accountId, symbol);
    }

    // ---- Reference data ----

    @GetMapping("/assets")
    @Operation(summary = "List assets", description = "Query params forwarded (status, asset_class, exchange, ...)")
    public JsonNode listAssets(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return broker.listAssets(query);
    }

    @GetMapping("/assets/{symbol}")
    public JsonNode getAsset(@PathVariable String symbol) {
        return broker.getAsset(symbol);
    }

    @GetMapping("/clock")
    public JsonNode getClock() {
        return broker.getClock();
    }

    @GetMapping("/calendar")
    public JsonNode getCalendar(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        return broker.getCalendar(query);
    }
}
