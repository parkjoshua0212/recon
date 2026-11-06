package com.recon.reconciliation.dto;

import jakarta.validation.constraints.NotNull;

public record ReconciliationRequest(@NotNull Long ledgerFeedId, @NotNull Long externalFeedId) {}
