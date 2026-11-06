package com.recon.reconciliation.service;

import com.recon.common.NotFoundException;
import com.recon.reconciliation.domain.Reconciliation;
import com.recon.reconciliation.domain.ReconciliationBreak;
import com.recon.reconciliation.repository.ReconciliationBreakRepository;
import com.recon.reconciliation.repository.ReconciliationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each method is its own transaction, so a failed run can still commit its FAILED status. */
@Service
public class ReconciliationWriter {

    private final ReconciliationRepository reconciliations;
    private final ReconciliationBreakRepository breaks;

    public ReconciliationWriter(ReconciliationRepository reconciliations, ReconciliationBreakRepository breaks) {
        this.reconciliations = reconciliations;
        this.breaks = breaks;
    }

    /** Breaks and the COMPLETED status commit together, so nobody can read a half-written result. */
    @Transactional
    public Reconciliation complete(long reconciliationId, List<ReconciliationBreak> found) {
        breaks.insertAll(reconciliationId, found);
        Reconciliation run = require(reconciliationId);
        run.complete();
        return run;
    }

    @Transactional
    public void fail(long reconciliationId, String reason) {
        require(reconciliationId).fail(reason);
    }

    private Reconciliation require(long reconciliationId) {
        return reconciliations
                .findById(reconciliationId)
                .orElseThrow(() -> new NotFoundException("Reconciliation " + reconciliationId + " not found"));
    }
}
