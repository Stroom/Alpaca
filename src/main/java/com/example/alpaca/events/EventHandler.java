package com.example.alpaca.events;

import tools.jackson.databind.JsonNode;

/** Processes one event (the JSON "data" of an SSE message) from an Alpaca event stream. */
public interface EventHandler {

    /** Name used for the resume cursor (event_stream_cursor.stream). */
    String stream();

    /** Broker API SSE path. */
    String path();

    /** Returns true when the event was stored (false for duplicates or events of unmanaged accounts). */
    boolean handle(JsonNode event);
}
