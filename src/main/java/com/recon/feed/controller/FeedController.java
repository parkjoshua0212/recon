package com.recon.feed.controller;

import com.recon.common.PageResponse;
import com.recon.feed.domain.FeedType;
import com.recon.feed.dto.FeedSummaryResponse;
import com.recon.feed.dto.FeedUploadResult;
import com.recon.feed.dto.RowErrorResponse;
import com.recon.feed.service.FeedService;
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

@RestController
@RequestMapping("/api/feeds")
public class FeedController {

    private static final int MAX_PAGE_SIZE = 200;

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @PostMapping(path = "/ledger", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FeedSummaryResponse> uploadLedger(@RequestParam("file") MultipartFile file) {
        return toResponse(feedService.upload(FeedType.LEDGER, file));
    }

    @PostMapping(path = "/external", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FeedSummaryResponse> uploadExternal(@RequestParam("file") MultipartFile file) {
        return toResponse(feedService.upload(FeedType.EXTERNAL, file));
    }

    @GetMapping("/{id}")
    public FeedSummaryResponse getFeed(@PathVariable long id) {
        return feedService.getSummary(id);
    }

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
