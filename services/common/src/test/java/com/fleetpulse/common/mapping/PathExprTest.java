package com.fleetpulse.common.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetpulse.common.event.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathExprTest {

    final JsonNode doc = read("""
            {"a":{"b":{"c":7}},"gps":[1.5,2.5],
             "signals":[{"name":"SPEED","value":40},{"name":"SOC","value":55}],
             "alarms":[{"code":"P0217"},{"level":"LOW"},{"code":"P0128"}]}
            """);

    static JsonNode read(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void navigatesFieldsIndexesFiltersAndWildcards() {
        assertThat(PathExpr.compile("a.b.c").eval(doc).asInt()).isEqualTo(7);
        assertThat(PathExpr.compile("gps[1]").eval(doc).asDouble()).isEqualTo(2.5);
        assertThat(PathExpr.compile("signals[name=SOC].value").eval(doc).asInt()).isEqualTo(55);
        JsonNode codes = PathExpr.compile("alarms[*].code").eval(doc);
        assertThat(codes.size()).isEqualTo(2);
        assertThat(codes.get(1).asText()).isEqualTo("P0128");
    }

    @Test
    void missingStepsYieldMissingNode() {
        assertThat(PathExpr.compile("a.x.c").eval(doc).isMissingNode()).isTrue();
        assertThat(PathExpr.compile("gps[9]").eval(doc).isMissingNode()).isTrue();
        assertThat(PathExpr.compile("signals[name=RPM].value").eval(doc).isMissingNode()).isTrue();
        assertThat(PathExpr.compile("a[name=x]").eval(doc).isMissingNode()).isTrue();   // filter on non-array
        assertThat(PathExpr.compile("a[*].b").eval(doc).isMissingNode()).isTrue();      // wildcard on non-array
    }

    @Test
    void rejectsInvalidSyntax() {
        assertThatThrownBy(() -> PathExpr.compile("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathExpr.compile("a..b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathExpr.compile("a[b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PathExpr.compile("x.y").toString()).isEqualTo("x.y");
    }
}
