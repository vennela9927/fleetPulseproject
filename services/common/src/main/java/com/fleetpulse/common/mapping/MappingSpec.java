package com.fleetpulse.common.mapping;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Declarative description of how one OEM's payload maps to {@code CanonicalEvent}.
 * Stored as JSON in Postgres ({@code oem_mapping.spec}) and published to the compacted
 * {@code oem.mappings} topic; normalizer instances hot-swap to a new version without restart.
 *
 * @param oem     OEM code, e.g. {@code AURORA}
 * @param version monotonically increasing per OEM
 * @param wmi     expected VIN prefix; events whose VIN does not start with it are rejected
 * @param fields  canonical field name → rule
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MappingSpec(String oem, int version, String wmi, Map<String, FieldRule> fields) {

    /**
     * @param path      where to read the value ({@link PathExpr} syntax); null when {@code constant} is used
     * @param transform how to convert it; see {@link Transform}
     * @param map       lookup table for the {@code map} transform (source text → target text)
     * @param format    date-time pattern for the {@code datetime} transform
     * @param constant  fixed value used when there is no path
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldRule(String path, String transform, Map<String, String> map, String format, String constant) {
        public static FieldRule of(String path) {
            return new FieldRule(path, null, null, null, null);
        }

        public static FieldRule of(String path, String transform) {
            return new FieldRule(path, transform, null, null, null);
        }
    }
}
