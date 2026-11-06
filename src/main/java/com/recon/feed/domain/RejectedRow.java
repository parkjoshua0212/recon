package com.recon.feed.domain;

public record RejectedRow(int lineNumber, String rawLine, String reason) implements RowOutcome {}
