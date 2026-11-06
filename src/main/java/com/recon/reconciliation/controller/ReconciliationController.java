package com.recon.reconciliation.controller;

import com.recon.common.PageResponse;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.dto.BreakResponse;
import com.recon.reconciliation.dto.ReconciliationRequest;
import com.recon.reconciliation.dto.ReconciliationSummaryResponse;
import com.recon.reconciliation.service.ReconciliationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliations")
public class ReconciliationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping
    public ResponseEntity<ReconciliationSummaryResponse> run(@Valid @RequestBody ReconciliationRequest request) {
        ReconciliationSummaryResponse summary = reconciliationService.run(request);
        return ResponseEntity.created(URI.create("/api/reconciliations/" + summary.id())).body(summary);
    }

    @GetMapping("/{id}")
    public ReconciliationSummaryResponse getSummary(@PathVariable long id) {
        return reconciliationService.getSummary(id);
    }

    @GetMapping("/{id}/breaks")
    public PageResponse<BreakResponse> getBreaks(
            @PathVariable long id,
            @RequestParam(required = false) BreakType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return reconciliationService.getBreaks(id, type, page, size);
    }
}
