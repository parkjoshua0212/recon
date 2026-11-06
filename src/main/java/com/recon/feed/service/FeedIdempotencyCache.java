package com.recon.feed.service;

import com.recon.feed.domain.FeedType;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis maps "feed type + file hash" to a feed ID so repeats skip the database. It is only a shortcut:
 * the UNIQUE constraint is the real guarantee, so any Redis problem counts as a cache miss and never
 * fails an upload.
 */
@Component
public class FeedIdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(FeedIdempotencyCache.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public FeedIdempotencyCache(
            StringRedisTemplate redis, @Value("${recon.feed.idempotency-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public Optional<Long> find(FeedType feedType, String fileHash) {
        try {
            String value = redis.opsForValue().get(key(feedType, fileHash));
            return value == null ? Optional.empty() : Optional.of(Long.valueOf(value));
        } catch (DataAccessException | NumberFormatException ex) {
            log.warn("Idempotency lookup failed, falling back to the database: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void remember(FeedType feedType, String fileHash, long feedId) {
        try {
            redis.opsForValue().set(key(feedType, fileHash), Long.toString(feedId), ttl);
        } catch (DataAccessException ex) {
            log.warn("Could not store idempotency key: {}", ex.getMessage());
        }
    }

    public static String key(FeedType feedType, String fileHash) {
        return "recon:feed:idempotency:" + feedType + ":" + fileHash;
    }
}
