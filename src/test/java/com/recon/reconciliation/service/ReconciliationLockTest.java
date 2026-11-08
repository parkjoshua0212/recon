package com.recon.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.recon.TestcontainersConfiguration;
import com.recon.common.ConflictException;
import com.recon.common.ServiceUnavailableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** Runs against real Redis, because the guarantees here come from Redis itself. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReconciliationLockTest {

    private static final AtomicLong NEXT_FEED_ID = new AtomicLong(1_000_000);

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ReconciliationLock lock;

    @Test
    void secondAcquireIsRejectedUntilTheFirstIsReleased() {
        long ledger = feedId();
        long external = feedId();

        ReconciliationLock.Handle first = lock.acquire(ledger, external);
        assertThatThrownBy(() -> lock.acquire(ledger, external)).isInstanceOf(ConflictException.class);
        first.close();

        lock.acquire(ledger, external).close();
    }

    @Test
    void lockAlwaysHasAnExpiry() {
        long ledger = feedId();
        long external = feedId();

        try (ReconciliationLock.Handle ignored = lock.acquire(ledger, external)) {
            assertThat(redis.getExpire(ReconciliationLock.key(ledger, external))).isPositive();
        }
    }

    /** What happens after a crash: nothing releases the lock, so the expiry has to. */
    @Test
    void expiredLockCanBeTakenAgain() {
        long ledger = feedId();
        long external = feedId();
        ReconciliationLock shortLived = new ReconciliationLock(redis, Duration.ofMillis(200));
        shortLived.acquire(ledger, external);

        await().atMost(Duration.ofSeconds(5))
                .until(() -> !redis.hasKey(ReconciliationLock.key(ledger, external)));

        lock.acquire(ledger, external).close();
    }

    /** A slow run whose lock expired must not release the lock now held by the next run. */
    @Test
    void releasingAnExpiredLockLeavesTheNewOwnersLockAlone() {
        long ledger = feedId();
        long external = feedId();
        String key = ReconciliationLock.key(ledger, external);
        ReconciliationLock.Handle slowRun = lock.acquire(ledger, external);
        redis.delete(key);
        ReconciliationLock.Handle nextRun = lock.acquire(ledger, external);

        slowRun.close();

        assertThat(redis.hasKey(key)).isTrue();
        assertThatThrownBy(() -> lock.acquire(ledger, external)).isInstanceOf(ConflictException.class);
        nextRun.close();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void redisOutageDuringAcquireIsReportedRatherThanIgnored() {
        StringRedisTemplate failing = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(failing.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> new ReconciliationLock(failing, Duration.ofMinutes(1)).acquire(1, 2))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void unclearRedisAnswerCountsAsNotAcquired() {
        StringRedisTemplate unclear = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(unclear.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThatThrownBy(() -> new ReconciliationLock(unclear, Duration.ofMinutes(1)).acquire(1, 2))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void redisOutageDuringReleaseDoesNotFailACompletedRun() {
        StringRedisTemplate failing = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(failing.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(failing.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        ReconciliationLock.Handle handle = new ReconciliationLock(failing, Duration.ofMinutes(1)).acquire(1, 2);

        assertThatCode(handle::close).doesNotThrowAnyException();
    }

    private long feedId() {
        return NEXT_FEED_ID.incrementAndGet();
    }
}
