package com.recon.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.reconciliation.dto.ReconciliationSummaryResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caches summaries of COMPLETED runs. Invalidation is simple here because a completed run is
 * immutable: its status and breaks never change and no endpoint can edit them, so there is no event
 * that makes a correct entry wrong. That is why ReconciliationService only stores COMPLETED runs. A
 * RUNNING summary would go stale the moment the run finished.
 *
 * <p>The TTL is a safety net: it limits memory use and how long a wrong entry could survive after
 * something outside the API changes the data, such as a wiped database. The version in the key lets
 * a changed response format start fresh instead of reading old JSON. If an endpoint ever deletes
 * reconciliations, it must evict the key too.
 *
 * <p>Redis problems count as a miss, because the database can always answer.
 */
@Component
public class ReconciliationSummaryCache {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSummaryCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public ReconciliationSummaryCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${recon.reconciliation.summary-cache-ttl}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Optional<ReconciliationSummaryResponse> find(long reconciliationId) {
        try {
            String json = redis.opsForValue().get(key(reconciliationId));
            return json == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(json, ReconciliationSummaryResponse.class));
        } catch (DataAccessException | JsonProcessingException ex) {
            log.warn("Summary cache read failed, using the database: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void store(ReconciliationSummaryResponse summary) {
        try {
            redis.opsForValue().set(key(summary.id()), objectMapper.writeValueAsString(summary), ttl);
        } catch (DataAccessException | JsonProcessingException ex) {
            log.warn("Could not cache summary {}: {}", summary.id(), ex.getMessage());
        }
    }

    public static String key(long reconciliationId) {
        return "recon:reconciliation:summary:v1:" + reconciliationId;
    }
}
