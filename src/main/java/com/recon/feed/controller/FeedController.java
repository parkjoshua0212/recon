package com.recon.feed.controller;

import com.recon.common.ApiError;
import com.recon.common.PageResponse;
import com.recon.feed.domain.FeedType;
import com.recon.feed.dto.FeedSummaryResponse;
import com.recon.feed.dto.FeedUploadResult;
import com.recon.feed.dto.RowErrorResponse;
import com.recon.feed.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Feeds", description = "Upload and inspect ledger and external statement CSV files")
@RestController
@RequestMapping("/api/feeds")
public class FeedController {

    private static final int MAX_PAGE_SIZE = 200;

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @Operation(
            summary = "Upload an internal ledger CSV",
            description = "Columns: txn_id, account_id, asset, amount, status, timestamp. Bad rows are saved "
                    + "with a reason while good rows are kept. Uploading the same bytes again returns the "
                    + "original feed.")
    @ApiResponse(responseCode = "201", description = "New feed stored")
    @ApiResponse(responseCode = "200", description = "Same file as before, original feed returned")
    @ApiResponse(responseCode = "400", description = "Unusable file, nothing stored",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "File is over 50 MB",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(path = "/ledger", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FeedSummaryResponse> uploadLedger(@RequestParam("file") MultipartFile file) {
        return toResponse(feedService.upload(FeedType.LEDGER, file));
    }

    @Operation(
            summary = "Upload an external statement CSV",
            description = "Columns: txn_id, account_id, asset, amount, status, timestamp. Bad rows are saved "
                    + "with a reason while good rows are kept. Uploading the same bytes again returns the "
                    + "original feed.")
    @ApiResponse(responseCode = "201", description = "New feed stored")
    @ApiResponse(responseCode = "200", description = "Same file as before, original feed returned")
    @ApiResponse(responseCode = "400", description = "Unusable file, nothing stored",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "File is over 50 MB",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(path = "/external", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FeedSummaryResponse> uploadExternal(@RequestParam("file") MultipartFile file) {
        return toResponse(feedService.upload(FeedType.EXTERNAL, file));
    }

    @Operation(summary = "Get a feed summary")
    @ApiResponse(responseCode = "200", description = "Feed found")
    @ApiResponse(responseCode = "404", description = "No such feed",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public FeedSummaryResponse getFeed(@PathVariable long id) {
        return feedService.getSummary(id);
    }

    @Operation(summary = "List a feed's rejected rows", description = "Each row includes its line number and reason.")
    @ApiResponse(responseCode = "200", description = "One page of rejected rows")
    @ApiResponse(responseCode = "400", description = "Invalid page or size",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such feed",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}/errors")
    public PageResponse<RowErrorResponse> getErrors(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return feedService.getErrors(id, page, size);
    }

    private ResponseEntity<FeedSummaryResponse> toResponse(FeedUploadResult result) {
        if (result.created()) {
            URI location = URI.create("/api/feeds/" + result.feed().id());
            return ResponseEntity.created(location).body(result.feed());
        }
        return ResponseEntity.ok(result.feed());
    }
}
