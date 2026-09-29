package com.fleetpulse.common.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny, compiled path language for locating a value inside an OEM payload.
 * Deliberately smaller than JSONPath so it can be compiled once and evaluated in
 * O(path length) with no reflection or regex on the hot path.
 *
 * <pre>
 *   location.lat              object fields
 *   gps[0]                    array index
 *   signals[name=SPEED].value first array element whose "name" equals "SPEED"
 *   alarms[*].code            every element's "code", returned as an array
 * </pre>
 */
public final class PathExpr {

    private static final Pattern TOKEN =
            Pattern.compile("^([A-Za-z0-9_\\-]*)(?:\\[(\\*|\\d+|[A-Za-z0-9_\\-]+=[^\\]]+)])?$");

    private sealed interface Segment permits Field, Index, Filter, Wildcard {}
    private record Field(String name) implements Segment {}
    private record Index(int index) implements Segment {}
    private record Filter(String key, String value) implements Segment {}
    private record Wildcard() implements Segment {}

    private final String source;
    private final Segment[] segments;

    private PathExpr(String source, Segment[] segments) {
        this.source = source;
        this.segments = segments;
    }

    public static PathExpr compile(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("empty path");
        List<Segment> out = new ArrayList<>();
        for (String token : path.split("\\.", -1)) {
            Matcher m = TOKEN.matcher(token);
            if (token.isEmpty() || !m.matches())
                throw new IllegalArgumentException("invalid path token '" + token + "' in " + path);
            if (!m.group(1).isEmpty()) out.add(new Field(m.group(1)));
            String sel = m.group(2);
            if (sel == null) continue;
            if (sel.equals("*")) out.add(new Wildcard());
            else if (sel.chars().allMatch(Character::isDigit)) out.add(new Index(Integer.parseInt(sel)));
            else {
                int eq = sel.indexOf('=');
                out.add(new Filter(sel.substring(0, eq), sel.substring(eq + 1)));
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("empty path");
        return new PathExpr(path, out.toArray(Segment[]::new));
    }

    /** Returns the value at this path, or {@link MissingNode} if any step is absent. */
    public JsonNode eval(JsonNode root) {
        return eval(root, 0);
    }

    private JsonNode eval(JsonNode node, int from) {
        for (int i = from; i < segments.length; i++) {
            if (node == null || node.isMissingNode() || node.isNull()) return MissingNode.getInstance();
            Segment s = segments[i];
            if (s instanceof Field f) {
                node = node.path(f.name());
            } else if (s instanceof Index ix) {
                node = node.path(ix.index());
            } else if (s instanceof Filter flt) {
                node = findFirst(node, flt);
            } else {
                // Wildcard: apply the rest of the path to every element and collect results.
                if (!node.isArray()) return MissingNode.getInstance();
                ArrayNode result = JsonNodeFactory.instance.arrayNode();
                for (JsonNode element : node) {
                    JsonNode v = eval(element, i + 1);
                    if (!v.isMissingNode() && !v.isNull()) result.add(v);
                }
                return result;
            }
        }
        return node == null ? MissingNode.getInstance() : node;
    }

    private static JsonNode findFirst(JsonNode array, Filter f) {
        if (!array.isArray()) return MissingNode.getInstance();
        for (JsonNode element : array) {
            if (f.value().equals(element.path(f.key()).asText(null))) return element;
        }
        return MissingNode.getInstance();
    }

    @Override
    public String toString() {
        return source;
    }
}
