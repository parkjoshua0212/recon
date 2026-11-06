package com.recon.feed.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A validated row. {@code txnTimeUtc} is already converted to UTC. */
public record ParsedTransaction(
        int lineNumber,
        String txnId,
        String accountId,
        String asset,
        BigDecimal amount,
        TransactionStatus status,
        LocalDateTime txnTimeUtc)
        implements RowOutcome {}
