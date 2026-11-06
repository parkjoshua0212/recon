package com.recon.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.recon.feed.domain.FeedType;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.MatchRow;
import com.recon.reconciliation.domain.ReconciliationBreak;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private final MatchingEngine engine = new MatchingEngine();

    @Test
    void identicalRowsProduceNoBreaks() {
        assertThat(engine.match(feed(row("T-1")), feed(row("T-1")))).isEmpty();
    }

    @Test
    void emptyFeedsProduceNoBreaks() {
        assertThat(engine.match(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void sameAmountWrittenWithDifferentScaleIsNotABreak() {
        MatchRow ledger = new MatchRow("T-1", "A", "BTC", new BigDecimal("1.5"), "SETTLED");
        MatchRow external = new MatchRow("T-1", "A", "BTC", new BigDecimal("1.500000000000000000"), "SETTLED");

        assertThat(engine.match(feed(ledger), feed(external))).isEmpty();
    }

    @Test
    void rowOnlyInLedgerIsMissingExternal() {
        List<ReconciliationBreak> breaks = engine.match(feed(row("T-1")), Map.of());

        assertThat(breaks).extracting(ReconciliationBreak::type).containsExactly(BreakType.MISSING_EXTERNAL);
        assertThat(breaks.get(0).ledgerAmount()).isEqualByComparingTo("1");
        assertThat(breaks.get(0).externalAmount()).isNull();
    }

    @Test
    void rowOnlyInExternalIsMissingInternal() {
        List<ReconciliationBreak> breaks = engine.match(Map.of(), feed(row("T-1")));

        assertThat(breaks).extracting(ReconciliationBreak::type).containsExactly(BreakType.MISSING_INTERNAL);
        assertThat(breaks.get(0).externalAmount()).isEqualByComparingTo("1");
        assertThat(breaks.get(0).ledgerAmount()).isNull();
    }

    @Test
    void smallestPossibleAmountDifferenceIsABreak() {
        MatchRow external = new MatchRow("T-1", "A", "BTC", new BigDecimal("1.000000000000000001"), "SETTLED");

        assertThat(engine.match(feed(row("T-1")), feed(external)))
                .extracting(ReconciliationBreak::type)
                .containsExactly(BreakType.AMOUNT_MISMATCH);
    }

    @Test
    void differentSignIsAnAmountBreak() {
        MatchRow external = new MatchRow("T-1", "A", "BTC", new BigDecimal("-1"), "SETTLED");

        assertThat(engine.match(feed(row("T-1")), feed(external)))
                .extracting(ReconciliationBreak::type)
                .containsExactly(BreakType.AMOUNT_MISMATCH);
    }

    @Test
    void differentStatusIsABreak() {
        MatchRow external = new MatchRow("T-1", "A", "BTC", BigDecimal.ONE, "PENDING");

        List<ReconciliationBreak> breaks = engine.match(feed(row("T-1")), feed(external));

        assertThat(breaks).extracting(ReconciliationBreak::type).containsExactly(BreakType.STATUS_MISMATCH);
        assertThat(breaks.get(0).ledgerStatus()).isEqualTo("SETTLED");
        assertThat(breaks.get(0).externalStatus()).isEqualTo("PENDING");
    }

    @Test
    void differentAssetIsABreakEvenWithTheSameAmount() {
        MatchRow external = new MatchRow("T-1", "A", "ETH", BigDecimal.ONE, "SETTLED");

        List<ReconciliationBreak> breaks = engine.match(feed(row("T-1")), feed(external));

        assertThat(breaks).extracting(ReconciliationBreak::type).containsExactly(BreakType.ASSET_MISMATCH);
        assertThat(breaks.get(0).ledgerAsset()).isEqualTo("BTC");
        assertThat(breaks.get(0).externalAsset()).isEqualTo("ETH");
    }

    @Test
    void differentAccountIsABreak() {
        MatchRow external = new MatchRow("T-1", "B", "BTC", BigDecimal.ONE, "SETTLED");

        List<ReconciliationBreak> breaks = engine.match(feed(row("T-1")), feed(external));

        assertThat(breaks).extracting(ReconciliationBreak::type).containsExactly(BreakType.ACCOUNT_MISMATCH);
        assertThat(breaks.get(0).ledgerAccountId()).isEqualTo("A");
        assertThat(breaks.get(0).externalAccountId()).isEqualTo("B");
    }

    @Test
    void everyDifferentFieldGetsItsOwnBreak() {
        MatchRow external = new MatchRow("T-1", "B", "ETH", new BigDecimal("2"), "FAILED");

        assertThat(engine.match(feed(row("T-1")), feed(external)))
                .extracting(ReconciliationBreak::type)
                .containsExactly(
                        BreakType.ASSET_MISMATCH,
                        BreakType.ACCOUNT_MISMATCH,
                        BreakType.AMOUNT_MISMATCH,
                        BreakType.STATUS_MISMATCH);
    }

    @Test
    void duplicateInOneFeedRecordsWhichFeedAndHowManyTimes() {
        Map<String, List<MatchRow>> ledger = Map.of("T-1", List.of(row("T-1"), row("T-1"), row("T-1")));

        List<ReconciliationBreak> breaks = engine.match(ledger, feed(row("T-1")));

        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).type()).isEqualTo(BreakType.DUPLICATE);
        assertThat(breaks.get(0).feedType()).isEqualTo(FeedType.LEDGER);
        assertThat(breaks.get(0).occurrences()).isEqualTo(3);
    }

    @Test
    void duplicatesOnBothSidesAreReportedSeparately() {
        Map<String, List<MatchRow>> both = Map.of("T-1", List.of(row("T-1"), row("T-1")));

        assertThat(engine.match(both, both))
                .extracting(ReconciliationBreak::feedType)
                .containsExactly(FeedType.LEDGER, FeedType.EXTERNAL);
    }

    @Test
    void duplicateDoesNotHideAMissingRow() {
        Map<String, List<MatchRow>> ledger = Map.of("T-1", List.of(row("T-1"), row("T-1")));

        assertThat(engine.match(ledger, Map.of()))
                .extracting(ReconciliationBreak::type)
                .containsExactly(BreakType.DUPLICATE, BreakType.MISSING_EXTERNAL);
    }

    @Test
    void duplicateDoesNotHideAFieldMismatch() {
        Map<String, List<MatchRow>> ledger = Map.of("T-1", List.of(row("T-1"), row("T-1")));
        MatchRow external = new MatchRow("T-1", "A", "BTC", new BigDecimal("2"), "SETTLED");

        assertThat(engine.match(ledger, feed(external)))
                .extracting(ReconciliationBreak::type)
                .containsExactly(BreakType.DUPLICATE, BreakType.AMOUNT_MISMATCH);
    }

    @Test
    void txnIdMatchingIsCaseSensitive() {
        assertThat(engine.match(feed(row("t-1")), feed(row("T-1"))))
                .extracting(ReconciliationBreak::type)
                .containsExactlyInAnyOrder(BreakType.MISSING_EXTERNAL, BreakType.MISSING_INTERNAL);
    }

    @Test
    void breaksComeBackInTxnIdOrder() {
        Map<String, List<MatchRow>> ledger = Map.of("T-3", List.of(row("T-3")), "T-1", List.of(row("T-1")));

        assertThat(engine.match(ledger, feed(row("T-2"))))
                .extracting(ReconciliationBreak::txnId)
                .containsExactly("T-1", "T-2", "T-3");
    }

    private MatchRow row(String txnId) {
        return new MatchRow(txnId, "A", "BTC", BigDecimal.ONE, "SETTLED");
    }

    private Map<String, List<MatchRow>> feed(MatchRow row) {
        return Map.of(row.txnId(), List.of(row));
    }
}
