package com.recon.common;

import java.time.Instant;

/** The single JSON error format returned by every endpoint. */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {}
