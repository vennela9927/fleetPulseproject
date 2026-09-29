package com.fleetpulse.common.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Value conversions available to mapping rules. Names are the lower-case enum names. */
public enum Transform {
    NONE,
    NUMBER,
    MPH_TO_KMH,
    MILES_TO_KM,
    F_TO_C,
    FRACTION_TO_PCT,
    ISO8601,
    EPOCH_MS,
    EPOCH_S,
    DATETIME,
    BOOL,
    MAP,
    CSV_LIST,
    LIST;

    public static Transform parse(String name) {
        if (name == null || name.isBlank()) return NONE;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown transform '" + name + "'");
        }
    }

    /**
     * @param node      the located value, never missing or null
     * @param lookup    table for {@link #MAP}
     * @param formatter parser for {@link #DATETIME}
     */
    Object apply(JsonNode node, Map<String, String> lookup, DateTimeFormatter formatter) {
        return switch (this) {
            case NONE -> plain(node);
            case NUMBER -> number(node);
            case MPH_TO_KMH -> number(node) * 1.609344;
            case MILES_TO_KM -> number(node) * 1.609344;
            case F_TO_C -> (number(node) - 32) * 5.0 / 9.0;
            case FRACTION_TO_PCT -> number(node) * 100.0;
            case ISO8601 -> iso(node.asText());
            case EPOCH_MS -> Instant.ofEpochMilli((long) number(node));
            case EPOCH_S -> Instant.ofEpochMilli(Math.round(number(node) * 1000));
            case DATETIME -> LocalDateTime.parse(node.asText(), formatter).toInstant(ZoneOffset.UTC);
            case BOOL -> bool(node);
            case MAP -> {
                String key = node.asText();
                String mapped = lookup == null ? null : lookup.get(key);
                if (mapped == null && lookup != null) mapped = lookup.get("*");   // explicit default
                yield mapped == null ? key : mapped;
            }
            case CSV_LIST -> csv(node.asText());
            case LIST -> list(node);
        };
    }

    private static Object plain(JsonNode node) {
        if (node.isNumber()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isArray()) return list(node);
        return node.asText();
    }

    static double number(JsonNode node) {
        if (node.isNumber()) return node.asDouble();
        String s = node.asText().trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new MappingException("FIELD_TYPE", "not a number: '" + s + "'");
        }
    }

    private static Instant iso(String s) {
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (Exception e2) {
                throw new MappingException("FIELD_TYPE", "not an ISO-8601 timestamp: '" + s + "'");
            }
        }
    }

    private static boolean bool(JsonNode node) {
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asDouble() != 0;
        return switch (node.asText().trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "on", "yes", "running", "drive", "run" -> true;
            default -> false;
        };
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("[,;|\\s]+")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    private static List<String> list(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) out.add(n.asText());
        } else {
            out.add(node.asText());
        }
        return out;
    }
}
