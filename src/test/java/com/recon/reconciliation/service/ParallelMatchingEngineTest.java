package com.recon.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.MatchRow;
import com.recon.reconciliation.domain.ReconciliationBreak;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ParallelMatchingEngineTest {

    private static final List<String> STATUSES = List.of("PENDING", "SETTLED", "FAILED");
    private static final List<String> ASSETS = List.of("BTC", "ETH", "USDT");

    private final MatchingEngine singleThreaded = new MatchingEngine();

    /** The core proof: identical output, including order, for random data and many configurations. */
    @ParameterizedTest
    @CsvSource({"1, 1", "1, 8", "2, 4", "4, 16", "8, 64", "16, 7", "4, 5000"})
    void matchesTheSingleThreadedResultExactly(int threads, int chunks) {
        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(singleThreaded, threads, chunks)) {
            for (long seed = 1; seed <= 25; seed++) {
                Random random = new Random(seed);
                Map<String, List<MatchRow>> ledger = randomFeed(random);
                Map<String, List<MatchRow>> external = randomFeed(random);

                List<ReconciliationBreak> expected = singleThreaded.match(ledger, external);

                assertThat(expected).isNotEmpty();
                assertThat(parallel.match(ledger, external)).containsExactlyElementsOf(expected);
            }
        }
    }

    /** Guards the proof above: it would mean little if the random data missed some break types. */
    @Test
    void randomDataCoversEveryBreakType() {
        Random random = new Random(1);

        Set<BreakType> types = EnumSet.noneOf(BreakType.class);
        singleThreaded.match(randomFeed(random), randomFeed(random)).forEach(item -> types.add(item.type()));

        assertThat(types).containsExactlyInAnyOrder(BreakType.values());
    }

    @Test
    void repeatedRunsGiveTheSameResult() {
        Random random = new Random(3);
        Map<String, List<MatchRow>> ledger = randomFeed(random);
        Map<String, List<MatchRow>> external = randomFeed(random);

        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(singleThreaded, 4, 16)) {
            List<ReconciliationBreak> first = parallel.match(ledger, external);
            for (int i = 0; i < 20; i++) {
                assertThat(parallel.match(ledger, external)).containsExactlyElementsOf(first);
            }
        }
    }

    @Test
    void runsConcurrentlyOnPoolThreads() {
        int threads = 4;
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        // Every task waits here until all four are running, which is only possible if they overlap.
        CyclicBarrier barrier = new CyclicBarrier(threads);
        MatchingEngine waiting = new MatchingEngine() {
            @Override
            public List<ReconciliationBreak> match(
                    Map<String, List<MatchRow>> ledger, Map<String, List<MatchRow>> external) {
                threadNames.add(Thread.currentThread().getName());
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                return super.match(ledger, external);
            }
        };

        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(waiting, threads, threads)) {
            parallel.match(Map.of(), Map.of());
        }

        assertThat(threadNames).hasSize(threads).allMatch(name -> name.startsWith("recon-matching-"));
    }

    @Test
    void manyRunsCanShareThePoolAtOnce() throws Exception {
        Random random = new Random(5);
        Map<String, List<MatchRow>> ledger = randomFeed(random);
        Map<String, List<MatchRow>> external = randomFeed(random);
        List<ReconciliationBreak> expected = singleThreaded.match(ledger, external);

        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(singleThreaded, 4, 16);
                ExecutorService callers = Executors.newFixedThreadPool(8)) {
            List<Future<List<ReconciliationBreak>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(callers.submit(() -> parallel.match(ledger, external)));
            }
            for (Future<List<ReconciliationBreak>> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).containsExactlyElementsOf(expected);
            }
        }
    }

    @Test
    void sameTxnIdAlwaysLandsInTheSameChunk() {
        assertThat(ParallelMatchingEngine.chunkOf("T-1", 16)).isEqualTo(ParallelMatchingEngine.chunkOf("T-1", 16));
    }

    @Test
    void negativeHashCodeStillGivesAValidChunk() {
        // This string's hashCode is Integer.MIN_VALUE, the one value Math.abs cannot make positive.
        String id = "polygenelubricants";
        assertThat(id.hashCode()).isEqualTo(Integer.MIN_VALUE);

        assertThat(ParallelMatchingEngine.chunkOf(id, 7)).isBetween(0, 6);
        assertThat(ParallelMatchingEngine.chunkOf("-negative-hash-", 7)).isBetween(0, 6);
    }

    @Test
    void differentIdsWithTheSameHashCodeStayDistinct() {
        // "Aa" and "BB" have the same hashCode, so they share a chunk but are still separate IDs.
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        Map<String, List<MatchRow>> ledger = feedOf(row("Aa", "1"), row("BB", "1"));
        Map<String, List<MatchRow>> external = feedOf(row("Aa", "1"), row("BB", "2"));

        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(singleThreaded, 2, 4)) {
            assertThat(parallel.match(ledger, external))
                    .extracting(ReconciliationBreak::txnId)
                    .containsExactly("BB");
        }
    }

    @Test
    void emptyFeedsProduceNoBreaks() {
        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(singleThreaded, 2, 4)) {
            assertThat(parallel.match(Map.of(), Map.of())).isEmpty();
        }
    }

    @Test
    void failureInAChunkReachesTheCallerAsTheOriginalException() {
        IllegalArgumentException failure = new IllegalArgumentException("boom");
        MatchingEngine failing = new MatchingEngine() {
            @Override
            public List<ReconciliationBreak> match(
                    Map<String, List<MatchRow>> ledger, Map<String, List<MatchRow>> external) {
                throw failure;
            }
        };

        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(failing, 2, 4)) {
            assertThatThrownBy(() -> parallel.match(Map.of(), Map.of())).isSameAs(failure);
        }
    }

    @Test
    void poolKeepsWorkingAfterAFailedRun() {
        boolean[] fail = {true};
        MatchingEngine failsOnce = new MatchingEngine() {
            @Override
            public List<ReconciliationBreak> match(
                    Map<String, List<MatchRow>> ledger, Map<String, List<MatchRow>> external) {
                if (fail[0]) {
                    throw new IllegalArgumentException("boom");
                }
                return super.match(ledger, external);
            }
        };

        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(failsOnce, 2, 4)) {
            assertThatThrownBy(() -> parallel.match(Map.of(), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            fail[0] = false;

            assertThat(parallel.match(feedOf(row("T-1", "1")), Map.of())).hasSize(1);
        }
    }

    @Test
    void interruptionIsPassedOnRatherThanSwallowed() {
        try (ParallelMatchingEngine parallel = new ParallelMatchingEngine(singleThreaded, 2, 4)) {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> parallel.match(Map.of(), Map.of())).isInstanceOf(IllegalStateException.class);
            // interrupted() also clears the flag, so it cannot leak into the next test.
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new ParallelMatchingEngine(singleThreaded, 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParallelMatchingEngine(singleThreaded, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** IDs are drawn from a limited pool, which produces duplicates and one-sided IDs by chance. */
    private Map<String, List<MatchRow>> randomFeed(Random random) {
        Map<String, List<MatchRow>> feed = new HashMap<>();
        for (int i = 0; i < 2_000; i++) {
            String txnId = "T-" + random.nextInt(1_800);
            MatchRow row = new MatchRow(
                    txnId,
                    "ACC-" + random.nextInt(3),
                    ASSETS.get(random.nextInt(ASSETS.size())),
                    new BigDecimal(random.nextInt(3)),
                    STATUSES.get(random.nextInt(STATUSES.size())));
            feed.computeIfAbsent(txnId, id -> new ArrayList<>()).add(row);
        }
        return feed;
    }

    private MatchRow row(String txnId, String amount) {
        return new MatchRow(txnId, "A", "BTC", new BigDecimal(amount), "SETTLED");
    }

    private Map<String, List<MatchRow>> feedOf(MatchRow... rows) {
        Map<String, List<MatchRow>> feed = new HashMap<>();
        for (MatchRow row : rows) {
            feed.computeIfAbsent(row.txnId(), id -> new ArrayList<>()).add(row);
        }
        return feed;
    }
}
