package com.fleetpulse.stream.alert;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alert sink inserts {@code rule_code} values that must exist in {@code alert_rule}
 * (foreign key), with the same severity. This reads the seed script so a rename on either
 * side fails the build instead of every insert at runtime.
 */
class AlertRuleContractTest {

    @Test
    void everyStreamRuleExistsInTheReferenceDataWithTheSameSeverity() throws Exception {
        String sql = Files.readString(Path.of("../../infra/postgres/init/03_reference_data.sql"));
        Matcher m = Pattern.compile("\\('([A-Z0-9_]+)',\\s*'[^']*',\\s*'(INFO|WARNING|CRITICAL)'\\)").matcher(sql);
        Map<String, String> seeded = new HashMap<>();
        while (m.find()) seeded.put(m.group(1), m.group(2));

        assertThat(seeded).isNotEmpty();
        for (AlertRule r : AlertRule.values()) {
            assertThat(seeded).as("alert_rule row for " + r).containsEntry(r.name(), r.severity);
        }
    }
}
