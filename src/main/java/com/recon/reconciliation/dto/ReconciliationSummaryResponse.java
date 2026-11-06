package com.recon.reconciliation.dto;

import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.Reconciliation;
import com.recon.reconciliation.domain.ReconciliationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record ReconciliationSummaryResponse(
        Long id,
        Long ledgerFeedId,
        Long externalFeedId,
        ReconciliationStatus status,
        Instant startedAt,
        Instant finishedAt,
        Long durationMillis,
        String failureReason,
        long totalBreaks,
        Map<BreakType, Long> breakCounts) {

    public static ReconciliationSummaryResponse from(Reconciliation run, Map<BreakType, Long> breakCounts) {
        Long duration = run.getFinishedAt() == null
                ? null
                : Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis();
        long total = breakCounts.values().stream().mapToLong(Long::longValue).sum();
        return new ReconciliationSummaryResponse(
                run.getId(),
                run.getLedgerFeedId(),
                run.getExternalFeedId(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                duration,
                run.getFailureReason(),
                total,
                breakCounts);
    }
}
