package com.recon.reconciliation.domain;

import com.recon.feed.domain.FeedType;
import java.math.BigDecimal;

/**
 * One reported difference. Every break keeps the values from both sides, where they exist, so a
 * reader can see the whole transaction without looking anything else up. {@code feedType} and
 * {@code occurrences} are only set on DUPLICATE breaks.
 */
public record ReconciliationBreak(
        BreakType type,
        String txnId,
        FeedType feedType,
        BigDecimal ledgerAmount,
        BigDecimal externalAmount,
        String ledgerStatus,
        String externalStatus,
        String ledgerAsset,
        String externalAsset,
        String ledgerAccountId,
        String externalAccountId,
        Integer occurrences) {

    public static ReconciliationBreak of(BreakType type, String txnId, MatchRow ledger, MatchRow external) {
        return build(type, txnId, null, ledger, external, null);
    }

    public static ReconciliationBreak duplicate(
            String txnId, FeedType feedType, MatchRow ledger, MatchRow external, int occurrences) {
        return build(BreakType.DUPLICATE, txnId, feedType, ledger, external, occurrences);
    }

    private static ReconciliationBreak build(
            BreakType type,
            String txnId,
            FeedType feedType,
            MatchRow ledger,
            MatchRow external,
            Integer occurrences) {
        return new ReconciliationBreak(
                type,
                txnId,
                feedType,
                ledger == null ? null : ledger.amount(),
                external == null ? null : external.amount(),
                ledger == null ? null : ledger.status(),
                external == null ? null : external.status(),
                ledger == null ? null : ledger.asset(),
                external == null ? null : external.asset(),
                ledger == null ? null : ledger.accountId(),
                external == null ? null : external.accountId(),
                occurrences);
    }
}
