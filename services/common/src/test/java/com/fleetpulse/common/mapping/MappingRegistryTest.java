package com.fleetpulse.common.mapping;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MappingRegistryTest {

    static String spec(String oem, int version, String vinPath) {
        return """
                {"oem":"%s","version":%d,"fields":{"vin":{"path":"%s"},"seq":{"path":"s"},
                 "ts":{"path":"t","transform":"epoch_ms"},"lat":{"path":"la"},"lon":{"path":"lo"}}}
                """.formatted(oem, version, vinPath);
    }

    @Test
    void activatesNewerVersionsAndNotifies() {
        MappingRegistry r = new MappingRegistry();
        List<String> seen = new ArrayList<>();
        r.onActivated((oem, m) -> seen.add(oem + "v" + m.spec().version()));

        assertThat(r.apply("DRACO", spec("DRACO", 1, "a"))).isTrue();
        assertThat(r.apply("DRACO", spec("DRACO", 1, "b"))).isFalse();   // same version: replay is a no-op
        assertThat(r.apply("draco", spec("DRACO", 2, "b"))).isTrue();
        assertThat(r.apply("DRACO", spec("DRACO", 1, "c"))).isFalse();   // older version ignored

        assertThat(r.get("Draco").spec().version()).isEqualTo(2);
        assertThat(r.versions()).containsEntry("DRACO", 2);
        assertThat(seen).containsExactly("DRACOv1", "DRACOv2");
    }

    @Test
    void brokenSpecKeepsThePreviousVersion() {
        MappingRegistry r = new MappingRegistry();
        r.apply("AURORA", spec("AURORA", 1, "vin"));
        assertThat(r.apply("AURORA", "{\"oem\":\"AURORA\",\"version\":2,\"fields\":{\"vin\":{\"path\":\"a..b\"}}}")).isFalse();
        assertThat(r.apply("AURORA", "not json")).isFalse();
        assertThat(r.apply("CYGNUS", spec("AURORA", 3, "vin"))).isFalse();   // key/spec mismatch
        assertThat(r.apply(null, "{}")).isFalse();
        assertThat(r.get("AURORA").spec().version()).isEqualTo(1);
        assertThat(r.get("UNKNOWN")).isNull();
        assertThat(r.get(null)).isNull();
    }
}
