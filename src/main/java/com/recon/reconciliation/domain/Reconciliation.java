package com.recon.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reconciliation")
public class Reconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ledger_feed_id", nullable = false, updatable = false)
    private Long ledgerFeedId;

    @Column(name = "external_feed_id", nullable = false, updatable = false)
    private Long externalFeedId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private ReconciliationStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    protected Reconciliation() {
        // Required by JPA.
    }

    private Reconciliation(Long ledgerFeedId, Long externalFeedId) {
        this.ledgerFeedId = ledgerFeedId;
        this.externalFeedId = externalFeedId;
        this.status = ReconciliationStatus.RUNNING;
        this.startedAt = now();
    }

    public static Reconciliation start(Long ledgerFeedId, Long externalFeedId) {
        return new Reconciliation(ledgerFeedId, externalFeedId);
    }

    public void complete() {
        this.status = ReconciliationStatus.COMPLETED;
        this.finishedAt = now();
    }

    public void fail(String reason) {
        this.status = ReconciliationStatus.FAILED;
        this.finishedAt = now();
        this.failureReason = reason;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getLedgerFeedId() {
        return ledgerFeedId;
    }

    public Long getExternalFeedId() {
        return externalFeedId;
    }

    public ReconciliationStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
