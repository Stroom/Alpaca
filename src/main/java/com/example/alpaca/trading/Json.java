package com.example.alpaca.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Null-safe readers for Alpaca JSON (numbers are often sent as strings, unset times as 0001-01-01). */
public final class Json {

    private Json() {
    }

    public static String str(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String s = v.asString();
        return s == null || s.isBlank() ? null : s;
    }

    public static BigDecimal dec(JsonNode node, String field) {
        String s = str(node, field);
        return s == null ? null : new BigDecimal(s);
    }

    public static OffsetDateTime ts(JsonNode node, String field) {
        String s = str(node, field);
        return s == null || s.startsWith("0001-") ? null : OffsetDateTime.parse(s);
    }

    public static LocalDate date(JsonNode node, String field) {
        String s = str(node, field);
        return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    public static UUID uuid(JsonNode node, String field) {
        String s = str(node, field);
        return s == null ? null : UUID.fromString(s);
    }

    public static Boolean bool(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asBoolean();
    }
}
