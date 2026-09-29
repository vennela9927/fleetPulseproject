package com.fleetpulse.gateway;

import com.fleetpulse.common.mapping.MappingRegistry;
import com.fleetpulse.common.vin.Vin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
@ActiveProfiles("test")
class IngestControllerTest {

    static final String KEY = "dev-oem-partner-key";
    static final String VIN = Vin.generate("AUR", "MT4C2", 2024, 'A', 1);

    @TestConfiguration
    static class Config {
        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }

        @Bean
        MappingRegistry mappingRegistry() {
            MappingRegistry r = new MappingRegistry();
            r.apply("AURORA", """
                    {"oem":"AURORA","version":1,"fields":{"vin":{"path":"vin"},"seq":{"path":"seq"},
                     "ts":{"path":"timestamp","transform":"iso8601"},"lat":{"path":"location.lat"},"lon":{"path":"location.lng"}}}""");
            return r;
        }
    }

    @Autowired MockMvc mvc;
    @MockBean RawPublisher publisher;

    @BeforeEach
    void capacity() {
        reset(publisher);
        when(publisher.hasCapacity(anyInt())).thenReturn(true);
    }

    String batch(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"vin\":\"").append(VIN).append("\",\"seq\":").append(i).append('}');
        }
        return sb.append(']').toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsBatchAndKeysByVin() throws Exception {
        mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", KEY).header("X-Request-Id", "r-1")
                        .contentType("application/json").content(batch(3)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.requestId").value("r-1"));
        ArgumentCaptor<List<RawPublisher.Raw>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishAndAwait(eq("AURORA"), eq("r-1"), captor.capture());
        assertThat(captor.getValue()).hasSize(3).allSatisfy(r -> assertThat(r.vin()).isEqualTo(VIN));
        assertThat(new String(captor.getValue().get(0).payload(), StandardCharsets.UTF_8)).contains("\"seq\":0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unonboardedOemIsAcceptedWithoutPartitionKey() throws Exception {
        mvc.perform(post("/v1/ingest/draco").header("X-Api-Key", KEY).contentType("application/json").content(batch(2)))
                .andExpect(status().isAccepted());
        ArgumentCaptor<List<RawPublisher.Raw>> captor = ArgumentCaptor.forClass(List.class);
        verify(publisher).publishAndAwait(eq("DRACO"), anyString(), captor.capture());
        assertThat(captor.getValue()).allSatisfy(r -> assertThat(r.vin()).isNull());
    }

    @Test
    void rejectsMissingOrWrongKey() throws Exception {
        mvc.perform(post("/v1/ingest/aurora").contentType("application/json").content(batch(1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", "guess").contentType("application/json").content(batch(1)))
                .andExpect(status().isUnauthorized());
        verify(publisher, never()).publishAndAwait(any(), any(), anyList());
    }

    @Test
    void rejectsUnknownOemBadBodyAndOversizedBatch() throws Exception {
        mvc.perform(post("/v1/ingest/zephyr").header("X-Api-Key", KEY).contentType("application/json").content(batch(1)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", KEY).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", KEY).contentType("application/json").content("[]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", KEY).contentType("application/json").content(batch(1001)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void appliesBackPressureWhenKafkaIsBehind() throws Exception {
        when(publisher.hasCapacity(anyInt())).thenReturn(false);
        mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", KEY).contentType("application/json").content(batch(1)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void opensCircuitAfterRepeatedBrokerFailures() throws Exception {
        doThrow(new RuntimeException("broker down")).when(publisher).publishAndAwait(any(), any(), anyList());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", KEY).contentType("application/json").content(batch(1)))
                    .andExpect(status().isServiceUnavailable());
        }
        reset(publisher);
        when(publisher.hasCapacity(anyInt())).thenReturn(true);
        // Circuit is open: rejected immediately without touching the broker.
        mvc.perform(post("/v1/ingest/aurora").header("X-Api-Key", KEY).contentType("application/json").content(batch(1)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
        verify(publisher, never()).publishAndAwait(any(), any(), anyList());
        mvc.perform(get("/v1/ingest/status")).andExpect(jsonPath("$.circuit").value("OPEN"))
                .andExpect(jsonPath("$.mappings.AURORA").value(1));
    }
}
