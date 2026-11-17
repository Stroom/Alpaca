package com.example.alpaca.events;

import com.example.alpaca.broker.BrokerClient;
import com.example.alpaca.config.AlpacaProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps one long-lived SSE connection per event stream (trades, activities, funding) on a virtual thread.
 * After each processed event the event id (a ULID) is stored as cursor; on (re)connect the stream resumes with
 * since_id=cursor, so nothing is missed across disconnects or restarts and replays are idempotent.
 */
@Component
public class AlpacaEventStreams implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AlpacaEventStreams.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final BrokerClient broker;
    private final JdbcClient db;
    private final JsonMapper json;
    private final AlpacaProperties properties;
    private final List<EventHandler> handlers;
    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private volatile boolean running;

    public record StreamStatus(String stream, String path, boolean connected, String lastEventId, long received,
                               long stored, String lastError) {
    }

    private static final class State {
        final EventHandler handler;
        volatile boolean connected;
        volatile String lastError;
        final AtomicLong received = new AtomicLong();
        final AtomicLong stored = new AtomicLong();

        State(EventHandler handler) {
            this.handler = handler;
        }
    }

    public AlpacaEventStreams(BrokerClient broker, JdbcClient db, JsonMapper json, AlpacaProperties properties,
                              List<EventHandler> handlers) {
        this.broker = broker;
        this.db = db;
        this.json = json;
        this.properties = properties;
        this.handlers = handlers;
    }

    @Override
    public void start() {
        running = true;
        if (!properties.events().enabled()) {
            log.info("Alpaca event streams disabled");
            return;
        }
        for (EventHandler handler : handlers) {
            states.put(handler.stream(), new State(handler));
            threads.put(handler.stream(), Thread.ofVirtual().name("events-" + handler.stream()).start(() -> run(handler)));
        }
    }

    @Override
    public void stop() {
        running = false;
        threads.values().forEach(Thread::interrupt);
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public List<StreamStatus> statuses() {
        return states.values().stream()
                .map(st -> new StreamStatus(st.handler.stream(), st.handler.path(), st.connected,
                        cursor(st.handler.stream()), st.received.get(), st.stored.get(), st.lastError))
                .toList();
    }

    private void run(EventHandler handler) {
        State state = states.get(handler.stream());
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            try {
                String cursor = cursor(handler.stream());
                MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
                if (cursor != null) {
                    query.add("since_id", cursor);
                } else {
                    query.add("since", LocalDate.now(ZoneOffset.UTC).minusDays(properties.events().initialLookbackDays()).toString());
                }
                broker.streamEvents(handler.path(), query,
                        () -> {
                            state.connected = true;
                            state.lastError = null;
                        },
                        event -> {
                            JsonNode node = json.readTree(event.data());
                            String eventId = node.path("event_id").asString(null);
                            state.received.incrementAndGet();
                            try {
                                if (handler.handle(node)) {
                                    state.stored.incrementAndGet();
                                }
                            } catch (RuntimeException ex) {
                                // Skip a single bad event instead of blocking the stream forever
                                log.error("Failed to process {} event {}: {}", handler.stream(), eventId, ex.toString(), ex);
                            }
                            if (eventId != null) {
                                saveCursor(handler.stream(), eventId);
                            }
                        });
                log.info("Event stream {} closed by Alpaca, reconnecting", handler.stream());
                backoff = Duration.ofSeconds(1);
            } catch (Exception ex) {
                if (!running) {
                    return;
                }
                state.lastError = ex.toString();
                log.warn("Event stream {} failed: {} (retrying in {}s)", handler.stream(), ex.toString(), backoff.toSeconds());
            }
            state.connected = false;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                return;
            }
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
    }

    private String cursor(String stream) {
        return db.sql("SELECT last_event_id FROM event_stream_cursor WHERE stream = :s")
                .param("s", stream).query(String.class).optional().orElse(null);
    }

    private void saveCursor(String stream, String eventId) {
        db.sql("""
                INSERT INTO event_stream_cursor (stream, last_event_id) VALUES (:s, :id)
                ON CONFLICT (stream) DO UPDATE SET last_event_id = EXCLUDED.last_event_id, updated_at = now()
                """).param("s", stream).param("id", eventId).update();
    }
}
