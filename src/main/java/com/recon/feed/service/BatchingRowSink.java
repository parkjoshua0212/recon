package com.recon.feed.service;

import com.recon.feed.domain.ParsedTransaction;
import com.recon.feed.domain.RejectedRow;
import com.recon.feed.repository.FeedRowErrorRepository;
import com.recon.feed.repository.FeedTransactionRepository;
import java.util.ArrayList;
import java.util.List;

/** Holds rows in small buffers and writes them in batches, keeping memory use flat. */
class BatchingRowSink implements FeedRowSink {

    private static final int BATCH_SIZE = 1_000;

    private final long feedId;
    private final FeedTransactionRepository transactions;
    private final FeedRowErrorRepository rowErrors;
    private final List<ParsedTransaction> transactionBuffer = new ArrayList<>(BATCH_SIZE);
    private final List<RejectedRow> errorBuffer = new ArrayList<>(BATCH_SIZE);
    private int accepted;
    private int rejected;

    BatchingRowSink(
            long feedId, FeedTransactionRepository transactions, FeedRowErrorRepository rowErrors) {
        this.feedId = feedId;
        this.transactions = transactions;
        this.rowErrors = rowErrors;
    }

    @Override
    public void accept(ParsedTransaction transaction) {
        accepted++;
        transactionBuffer.add(transaction);
        if (transactionBuffer.size() == BATCH_SIZE) {
            flushTransactions();
        }
    }

    @Override
    public void reject(RejectedRow row) {
        rejected++;
        errorBuffer.add(row);
        if (errorBuffer.size() == BATCH_SIZE) {
            flushErrors();
        }
    }

    void flush() {
        flushTransactions();
        flushErrors();
    }

    int accepted() {
        return accepted;
    }

    int rejected() {
        return rejected;
    }

    private void flushTransactions() {
        if (!transactionBuffer.isEmpty()) {
            transactions.insertBatch(feedId, transactionBuffer);
            transactionBuffer.clear();
        }
    }

    private void flushErrors() {
        if (!errorBuffer.isEmpty()) {
            rowErrors.insertBatch(feedId, errorBuffer);
            errorBuffer.clear();
        }
    }
}
