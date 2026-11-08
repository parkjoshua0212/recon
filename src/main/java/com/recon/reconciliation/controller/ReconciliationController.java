package com.recon.reconciliation.controller;

import com.recon.common.ApiError;
import com.recon.common.PageResponse;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.dto.BreakResponse;
import com.recon.reconciliation.dto.ReconciliationRequest;
import com.recon.reconciliation.dto.ReconciliationSummaryResponse;
import com.recon.reconciliation.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Reconciliations", description = "Compare two feeds and read the breaks found")
@RestController
@RequestMapping("/api/reconciliations")
public class ReconciliationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Operation(
            summary = "Run a reconciliation",
            description = "Compares a ledger feed with an external feed and stores every break. Only one run "
                    + "per pair of feeds can be active at a time.")
    @ApiResponse(responseCode = "201", description = "Run finished")
    @ApiResponse(responseCode = "400", description = "Missing field, or a feed of the wrong type",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such feed",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "This pair of feeds is already being reconciled",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "The lock service is unavailable, try again later",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    public ResponseEntity<ReconciliationSummaryResponse> run(@Valid @RequestBody ReconciliationRequest request) {
        ReconciliationSummaryResponse summary = reconciliationService.run(request);
        return ResponseEntity.created(URI.create("/api/reconciliations/" + summary.id())).body(summary);
    }

    @Operation(summary = "Get a reconciliation summary", description = "Includes status, duration, and counts per break type.")
    @ApiResponse(responseCode = "200", description = "Reconciliation found")
    @ApiResponse(responseCode = "404", description = "No such reconciliation",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public ReconciliationSummaryResponse getSummary(@PathVariable long id) {
        return reconciliationService.getSummary(id);
    }

    @Operation(summary = "List breaks", description = "Paginated, with an optional filter by break type.")
    @ApiResponse(responseCode = "200", description = "One page of breaks")
    @ApiResponse(responseCode = "400", description = "Invalid type, page, or size",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such reconciliation",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}/breaks")
    public PageResponse<BreakResponse> getBreaks(
            @PathVariable long id,
            @RequestParam(required = false) BreakType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return reconciliationService.getBreaks(id, type, page, size);
    }
}
