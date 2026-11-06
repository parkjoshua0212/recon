package com.recon.feed.dto;

import com.recon.feed.domain.Feed;
import com.recon.feed.domain.FeedType;
import java.time.Instant;

public record FeedSummaryResponse(
        Long id,
        FeedType feedType,
        String fileHash,
        String originalFilename,
        int totalRows,
        int acceptedRows,
        int rejectedRows,
        Instant uploadedAt) {

    public static FeedSummaryResponse from(Feed feed) {
        return new FeedSummaryResponse(
                feed.getId(),
                feed.getFeedType(),
                feed.getFileHash(),
                feed.getOriginalFilename(),
                feed.getTotalRows(),
                feed.getAcceptedRows(),
                feed.getRejectedRows(),
                feed.getUploadedAt());
    }
}
