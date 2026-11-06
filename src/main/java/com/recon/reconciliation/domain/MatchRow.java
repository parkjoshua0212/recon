package com.recon.reconciliation.domain;

import java.math.BigDecimal;

/** The fields of a stored transaction that take part in matching. */
public record MatchRow(String txnId, String accountId, String asset, BigDecimal amount, String status) {}
