package com.recon.reconciliation.service;

import com.recon.common.InvalidRequestException;
import com.recon.common.NotFoundException;
import com.recon.common.PageResponse;
import com.recon.feed.domain.Feed;
import com.recon.feed.domain.FeedType;
import com.recon.feed.repository.FeedRepository;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.MatchRow;
import com.recon.reconciliation.domain.Reconciliation;
import com.recon.reconciliation.domain.ReconciliationBreak;
import com.recon.reconciliation.dto.BreakResponse;
import com.recon.reconciliation.dto.ReconciliationRequest;
import com.recon.reconciliation.dto.ReconciliationSummaryResponse;
import com.recon.reconciliation.repository.MatchRowRepository;
import com.recon.reconciliation.repository.ReconciliationBreakRepository;
import com.recon.reconciliation.repository.ReconciliationRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final FeedRepository feeds;
    private final ReconciliationRepository reconciliations;
    private final ReconciliationBreakRepository breaks;
    private final MatchRowRepository matchRows;
    private final MatchingEngine engine;
    private final ReconciliationWriter writer;

    public ReconciliationService(
            FeedRepository feeds,
            ReconciliationRepository reconciliations,
            ReconciliationBreakRepository breaks,
            MatchRowRepository matchRows,
            MatchingEngine engine,
            ReconciliationWriter writer) {
        this.feeds = feeds;
        this.reconciliations = reconciliations;
        this.breaks = breaks;
        this.matchRows = matchRows;
        this.engine = engine;
        this.writer = writer;
    }

    /**
     * Not @Transactional: the RUNNING row must commit first, so a crash leaves a visible record, and
     * matching must not hold a database transaction open.
     */
    public ReconciliationSummaryResponse run(ReconciliationRequest request) {
        requireFeed(request.ledgerFeedId(), FeedType.LEDGER);
        requireFeed(request.externalFeedId(), FeedType.EXTERNAL);

        Reconciliation run = reconciliations.save(
                Reconciliation.start(request.ledgerFeedId(), request.externalFeedId()));
        try {
            Map<String, List<MatchRow>> ledger = matchRows.loadGroupedByTxnId(request.ledgerFeedId());
            Map<String, List<MatchRow>> external = matchRows.loadGroupedByTxnId(request.externalFeedId());
            List<ReconciliationBreak> found = engine.match(ledger, external);
            Reconciliation finished = writer.complete(run.getId(), found);
            return toSummary(finished);
        } catch (RuntimeException ex) {
            log.error("Reconciliation {} failed", run.getId(), ex);
            // A fixed message, because exception text can contain internal details.
            writer.fail(run.getId(), "Reconciliation failed, see server logs");
            throw ex;
        }
    }

    public ReconciliationSummaryResponse getSummary(long reconciliationId) {
        return toSummary(requireReconciliation(reconciliationId));
    }

    public PageResponse<BreakResponse> getBreaks(long reconciliationId, BreakType type, int page, int size) {
        requireReconciliation(reconciliationId);
        Map<BreakType, Long> counts = breaks.countByType(reconciliationId);
        long offset = (long) page * size;
        List<ReconciliationBreak> items;
        long total;
        if (type == null) {
            items = breaks.findPage(reconciliationId, size, offset);
            total = counts.values().stream().mapToLong(Long::longValue).sum();
        } else {
            items = breaks.findPageByType(reconciliationId, type, size, offset);
            total = counts.get(type);
        }
        return PageResponse.of(items.stream().map(BreakResponse::from).toList(), page, size, total);
    }

    private ReconciliationSummaryResponse toSummary(Reconciliation run) {
        return ReconciliationSummaryResponse.from(run, breaks.countByType(run.getId()));
    }

    private void requireFeed(long feedId, FeedType expectedType) {
        Feed feed = feeds.findById(feedId)
                .orElseThrow(() -> new NotFoundException("Feed " + feedId + " not found"));
        if (feed.getFeedType() != expectedType) {
            throw new InvalidRequestException("Feed " + feedId + " is not a " + expectedType + " feed");
        }
    }

    private Reconciliation requireReconciliation(long reconciliationId) {
        return reconciliations
                .findById(reconciliationId)
                .orElseThrow(() -> new NotFoundException("Reconciliation " + reconciliationId + " not found"));
    }
}
