package com.recon.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.recon.feed.domain.FeedType;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class FeedIdempotencyCacheTest {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final FeedIdempotencyCache cache = new FeedIdempotencyCache(redis, TTL);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void keyIncludesFeedTypeAndHash() {
        assertThat(FeedIdempotencyCache.key(FeedType.EXTERNAL, "abc"))
                .isEqualTo("recon:feed:idempotency:EXTERNAL:abc");
    }

    @Test
    void storesFeedIdWithExpiry() {
        cache.remember(FeedType.LEDGER, "abc", 7L);

        verify(values).set("recon:feed:idempotency:LEDGER:abc", "7", TTL);
    }

    @Test
    void returnsStoredFeedId() {
        when(values.get(anyString())).thenReturn("7");

        assertThat(cache.find(FeedType.LEDGER, "abc")).contains(7L);
    }

    @Test
    void treatsRedisOutageAsACacheMiss() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.find(FeedType.LEDGER, "abc")).isEmpty();
    }

    @Test
    void treatsCorruptValueAsACacheMiss() {
        when(values.get(anyString())).thenReturn("not-a-number");

        assertThat(cache.find(FeedType.LEDGER, "abc")).isEmpty();
    }

    @Test
    void redisOutageOnWriteDoesNotFailTheUpload() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.remember(FeedType.LEDGER, "abc", 7L)).doesNotThrowAnyException();
    }
}
