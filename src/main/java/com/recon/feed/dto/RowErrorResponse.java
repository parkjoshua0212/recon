package com.recon.feed.dto;

import com.recon.feed.domain.RejectedRow;

public record RowErrorResponse(int lineNumber, String rawLine, String reason) {

    public static RowErrorResponse from(RejectedRow row) {
        return new RowErrorResponse(row.lineNumber(), row.rawLine(), row.reason());
    }
}
