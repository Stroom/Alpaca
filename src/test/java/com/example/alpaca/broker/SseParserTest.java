package com.example.alpaca.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.alpaca.broker.BrokerClient.SseEvent;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    @Test
    void parsesEventsAndSkipsComments() throws Exception {
        String stream = """
                : heartbeat

                id: 01ABC
                data: {"event":"fill",
                data: "qty":"1"}

                event: custom
                data: x

                """;
        List<SseEvent> events = new ArrayList<>();
        BrokerClient.readEvents(new BufferedReader(new StringReader(stream)), events::add);

        assertThat(events).containsExactly(
                new SseEvent("01ABC", null, "{\"event\":\"fill\",\n\"qty\":\"1\"}"),
                new SseEvent("01ABC", "custom", "x"));
    }
}
