package com.recon.feed.domain;

/** The result of checking one CSV row: it either becomes a transaction or is rejected with a reason. */
public sealed interface RowOutcome permits ParsedTransaction, RejectedRow {}
