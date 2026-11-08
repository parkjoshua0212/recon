package com.recon.reconciliation.service;

import com.recon.common.ConflictException;
import com.recon.common.ServiceUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Allows one run at a time per pair of feeds, across every instance of the app.
 *
 * <p>SET NX PX does the claim and the expiry as one atomic step, so there is no moment where the
 * lock exists without an expiry. The expiry frees the lock if the app crashes while holding it.
 *
 * <p>Unlike the upload idempotency key, the lock has no database backstop, so a Redis failure stops
 * the run rather than letting it continue without protection.
 */
@Component
public class ReconciliationLock {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationLock.class);

    // Checking and deleting must be one atomic step. As two separate commands, the lock could expire
    // between them and this would delete a lock that now belongs to another run.
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public ReconciliationLock(
            StringRedisTemplate redis, @Value("${recon.reconciliation.lock-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** Use with try-with-resources, so the lock is released however the run ends. */
    public Handle acquire(long ledgerFeedId, long externalFeedId) {
        String key = key(ledgerFeedId, externalFeedId);
        // A unique token proves ownership when the lock is released.
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        } catch (DataAccessException ex) {
            throw new ServiceUnavailableException("Could not reach the lock service, try again later", ex);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ConflictException(
                    "A reconciliation of feeds %d and %d is already running".formatted(ledgerFeedId, externalFeedId));
        }
        return new Handle(key, token);
    }

    public static String key(long ledgerFeedId, long externalFeedId) {
        return "recon:lock:reconciliation:" + ledgerFeedId + ":" + externalFeedId;
    }

    public final class Handle implements AutoCloseable {

        private final String key;
        private final String token;

        private Handle(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void close() {
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (DataAccessException ex) {
                // The result is already saved, and the expiry will free the lock.
                log.warn("Could not release lock {}: {}", key, ex.getMessage());
            }
        }
    }
}
