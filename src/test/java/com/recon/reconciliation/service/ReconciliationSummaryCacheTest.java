package com.recon.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.ReconciliationStatus;
import com.recon.reconciliation.dto.ReconciliationSummaryResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class ReconciliationSummaryCacheTest {

    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ReconciliationSummaryCache cache = new ReconciliationSummaryCache(redis, objectMapper, TTL);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void summarySurvivesStoringAndReadingBack() {
        ReconciliationSummaryResponse summary = summary();
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        cache.store(summary);

        verify(values).set(eq("recon:reconciliation:summary:v1:7"), json.capture(), eq(TTL));
        when(values.get(anyString())).thenReturn(json.getValue());
        assertThat(cache.find(7)).contains(summary);
    }

    @Test
    void missingEntryIsAMiss() {
        assertThat(cache.find(7)).isEmpty();
    }

    @Test
    void corruptEntryIsAMiss() {
        when(values.get(anyString())).thenReturn("{not json");

        assertThat(cache.find(7)).isEmpty();
    }

    @Test
    void redisOutageOnReadIsAMiss() {
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(cache.find(7)).isEmpty();
    }

    @Test
    void redisOutageOnWriteIsIgnored() {
        doThrow(new DataAccessResourceFailureException("down"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.store(summary())).doesNotThrowAnyException();
    }

    private ReconciliationSummaryResponse summary() {
        Map<BreakType, Long> counts = new EnumMap<>(BreakType.class);
        for (BreakType type : BreakType.values()) {
            counts.put(type, 2L);
        }
        Instant start = Instant.parse("2026-01-15T00:00:00.123456Z");
        return new ReconciliationSummaryResponse(
                7L, 1L, 2L, ReconciliationStatus.COMPLETED, start, start.plusMillis(5), 5L, null, 14L, counts);
    }
}
