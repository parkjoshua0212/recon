package com.recon.reconciliation.service;

import com.recon.reconciliation.domain.MatchRow;
import com.recon.reconciliation.domain.ReconciliationBreak;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

/**
 * Runs {@link MatchingEngine} on several threads and returns exactly what it would return alone.
 *
 * <p>Three things make that safe. A txn_id always hashes to the same chunk, so both sides of a
 * transaction are compared together. Each chunk owns its data and MatchingEngine keeps no state, so
 * threads share nothing that changes. And the merged result is sorted, so the order threads finish
 * in cannot reach the output.
 */
@Component
public class ParallelMatchingEngine implements AutoCloseable {

    private final MatchingEngine engine;
    private final int chunkCount;
    private final ExecutorService executor;

    public ParallelMatchingEngine(
            MatchingEngine engine,
            @Value("${recon.matching.threads}") int threads,
            @Value("${recon.matching.chunks}") int chunkCount) {
        if (threads < 1 || chunkCount < 1) {
            throw new IllegalArgumentException("threads and chunks must both be at least 1");
        }
        this.engine = engine;
        this.chunkCount = chunkCount;
        // Platform threads, because matching is CPU work and virtual threads only help with waiting.
        this.executor = Executors.newFixedThreadPool(threads, new CustomizableThreadFactory("recon-matching-"));
    }

    public List<ReconciliationBreak> match(
            Map<String, List<MatchRow>> ledger, Map<String, List<MatchRow>> external) {
        List<List<Entry<String, List<MatchRow>>>> ledgerChunks = split(ledger);
        List<List<Entry<String, List<MatchRow>>>> externalChunks = split(external);

        List<Future<List<ReconciliationBreak>>> futures = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            List<Entry<String, List<MatchRow>>> ledgerChunk = ledgerChunks.get(i);
            List<Entry<String, List<MatchRow>>> externalChunk = externalChunks.get(i);
            futures.add(executor.submit(() -> engine.match(toMap(ledgerChunk), toMap(externalChunk))));
        }
        return merge(futures);
    }

    /** floorMod rather than %, because hashCode can be negative and % would then be too. */
    static int chunkOf(String txnId, int chunkCount) {
        return Math.floorMod(txnId.hashCode(), chunkCount);
    }

    /**
     * Only sorts rows into lists. Building a map per chunk here took longer than the matching saved,
     * because this part runs on one thread, so that work happens inside each task instead.
     */
    private List<List<Entry<String, List<MatchRow>>>> split(Map<String, List<MatchRow>> feed) {
        List<List<Entry<String, List<MatchRow>>>> chunks = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            chunks.add(new ArrayList<>(feed.size() / chunkCount + 1));
        }
        for (Entry<String, List<MatchRow>> entry : feed.entrySet()) {
            chunks.get(chunkOf(entry.getKey(), chunkCount)).add(entry);
        }
        return chunks;
    }

    private Map<String, List<MatchRow>> toMap(List<Entry<String, List<MatchRow>>> entries) {
        Map<String, List<MatchRow>> map = HashMap.newHashMap(entries.size());
        for (Entry<String, List<MatchRow>> entry : entries) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private List<ReconciliationBreak> merge(List<Future<List<ReconciliationBreak>>> futures) {
        List<ReconciliationBreak> merged = new ArrayList<>();
        try {
            for (Future<List<ReconciliationBreak>> future : futures) {
                merged.addAll(future.get());
            }
        } catch (ExecutionException ex) {
            cancelAll(futures);
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Matching failed", ex.getCause());
        } catch (InterruptedException ex) {
            cancelAll(futures);
            // Puts the flag back so code further up can still see the interruption.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while matching", ex);
        }
        // A stable sort keeps each transaction's breaks in the order the engine produced them.
        merged.sort(Comparator.comparing(ReconciliationBreak::txnId));
        return merged;
    }

    private void cancelAll(List<Future<List<ReconciliationBreak>>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    /** Lets chunks already running finish, then releases the threads when the app stops. */
    @PreDestroy
    @Override
    public void close() {
        executor.shutdown();
    }
}
