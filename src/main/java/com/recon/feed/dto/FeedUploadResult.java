package com.recon.feed.dto;

/** {@code created} tells the controller to answer 201 for a new feed or 200 for a repeated file. */
public record FeedUploadResult(FeedSummaryResponse feed, boolean created) {}
