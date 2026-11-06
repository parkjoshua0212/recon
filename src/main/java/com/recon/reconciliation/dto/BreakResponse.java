package com.recon.reconciliation.dto;

import com.recon.feed.domain.FeedType;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.ReconciliationBreak;
import java.math.BigDecimal;

/**
 * Amounts are strings because many JSON clients read numbers as doubles, which would lose precision
 * on an 18-decimal crypto amount.
 */
public record BreakResponse(
        BreakType type,
        String txnId,
        FeedType feedType,
        String ledgerAmount,
        String externalAmount,
        String ledgerStatus,
        String externalStatus,
        String ledgerAsset,
        String externalAsset,
        String ledgerAccountId,
        String externalAccountId,
        Integer occurrences) {

    public static BreakResponse from(ReconciliationBreak item) {
        return new BreakResponse(
                item.type(),
                item.txnId(),
                item.feedType(),
                format(item.ledgerAmount()),
                format(item.externalAmount()),
                item.ledgerStatus(),
                item.externalStatus(),
                item.ledgerAsset(),
                item.externalAsset(),
                item.ledgerAccountId(),
                item.externalAccountId(),
                item.occurrences());
    }

    private static String format(BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }
}
