package com.example.alpaca.web;

import com.example.alpaca.broker.BrokerClient;
import com.example.alpaca.broker.BrokerClient.BrokerStreamException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/broker/events")
@Tag(name = "Broker events", description = "Order fulfilment updates (fills, partial fills, cancels)")
public class TradeEventsController {

    private static final Logger log = LoggerFactory.getLogger(TradeEventsController.class);

    private final BrokerClient broker;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    public TradeEventsController(BrokerClient broker) {
        this.broker = broker;
    }

    @GetMapping(value = "/trades", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream trade events (SSE)",
            description = "Relays /v2/events/trades. Query params forwarded (since, until, since_id, until_id). "
                    + "Try: curl -N http://localhost:8080/api/broker/events/trades")
    public SseEmitter tradeEvents(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        streams.submit(() -> {
            try {
                broker.streamTradeEvents(query,
                        () -> sendComment(emitter, "connected"),
                        event -> {
                            SseEmitter.SseEventBuilder out = SseEmitter.event().data(event.data(), MediaType.APPLICATION_JSON);
                            if (event.id() != null) {
                                out.id(event.id());
                            }
                            if (event.event() != null) {
                                out.name(event.event());
                            }
                            emitter.send(out); // throws IOException once the client disconnects, ending the stream
                        });
                emitter.complete();
            } catch (BrokerStreamException e) {
                log.warn(e.getMessage());
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.debug("Trade events stream ended: {}", e.toString());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private static void sendComment(SseEmitter emitter, String comment) {
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (Exception e) {
            throw new IllegalStateException("Client disconnected", e);
        }
    }
}
