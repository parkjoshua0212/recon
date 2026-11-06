package com.recon.reconciliation.service;

import com.recon.feed.domain.FeedType;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.MatchRow;
import com.recon.reconciliation.domain.ReconciliationBreak;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Compares two feeds. It touches no database, so every rule can be tested with plain objects.
 * Rows are grouped by txn_id, with the rows for one txn_id listed in file order.
 */
@Component
public class MatchingEngine {

    public List<ReconciliationBreak> match(
            Map<String, List<MatchRow>> ledger, Map<String, List<MatchRow>> external) {
        // Sorted IDs mean the same inputs always give breaks in the same order.
        TreeSet<String> txnIds = new TreeSet<>(ledger.keySet());
        txnIds.addAll(external.keySet());

        List<ReconciliationBreak> breaks = new ArrayList<>();
        for (String txnId : txnIds) {
            matchTransaction(
                    txnId, ledger.getOrDefault(txnId, List.of()), external.getOrDefault(txnId, List.of()), breaks);
        }
        return breaks;
    }

    private void matchTransaction(
            String txnId,
            List<MatchRow> ledgerRows,
            List<MatchRow> externalRows,
            List<ReconciliationBreak> breaks) {
        // Duplicates are reported, then the first row stands in for its side. Comparing it as well
        // means a duplicate cannot hide a wrong amount or status on the same transaction.
        MatchRow ledger = ledgerRows.isEmpty() ? null : ledgerRows.get(0);
        MatchRow external = externalRows.isEmpty() ? null : externalRows.get(0);

        if (ledgerRows.size() > 1) {
            breaks.add(ReconciliationBreak.duplicate(txnId, FeedType.LEDGER, ledger, external, ledgerRows.size()));
        }
        if (externalRows.size() > 1) {
            breaks.add(ReconciliationBreak.duplicate(txnId, FeedType.EXTERNAL, ledger, external, externalRows.size()));
        }

        if (external == null) {
            breaks.add(ReconciliationBreak.of(BreakType.MISSING_EXTERNAL, txnId, ledger, null));
        } else if (ledger == null) {
            breaks.add(ReconciliationBreak.of(BreakType.MISSING_INTERNAL, txnId, null, external));
        } else {
            comparePair(txnId, ledger, external, breaks);
        }
    }

    /** Each field is checked on its own, so one transaction can produce several breaks. */
    private void comparePair(String txnId, MatchRow ledger, MatchRow external, List<ReconciliationBreak> breaks) {
        if (!ledger.asset().equals(external.asset())) {
            breaks.add(ReconciliationBreak.of(BreakType.ASSET_MISMATCH, txnId, ledger, external));
        }
        if (!ledger.accountId().equals(external.accountId())) {
            breaks.add(ReconciliationBreak.of(BreakType.ACCOUNT_MISMATCH, txnId, ledger, external));
        }
        // compareTo, because BigDecimal.equals treats 1.5 and 1.50 as different.
        if (ledger.amount().compareTo(external.amount()) != 0) {
            breaks.add(ReconciliationBreak.of(BreakType.AMOUNT_MISMATCH, txnId, ledger, external));
        }
        if (!ledger.status().equals(external.status())) {
            breaks.add(ReconciliationBreak.of(BreakType.STATUS_MISMATCH, txnId, ledger, external));
        }
    }
}
