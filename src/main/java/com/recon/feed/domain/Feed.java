package com.recon.feed.domain;

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
@Table(name = "feed")
public class Feed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Without the explicit type, Hibernate expects a native MySQL ENUM and schema validation fails.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "feed_type", nullable = false, length = 16, updatable = false)
    private FeedType feedType;

    // The column is CHAR(64). Hibernate assumes VARCHAR for a String unless told otherwise.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_hash", nullable = false, length = 64, updatable = false)
    private String fileHash;

    @Column(name = "original_filename", updatable = false)
    private String originalFilename;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "accepted_rows", nullable = false)
    private int acceptedRows;

    @Column(name = "rejected_rows", nullable = false)
    private int rejectedRows;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected Feed() {
        // Required by JPA.
    }

    private Feed(FeedType feedType, String fileHash, String originalFilename, Instant uploadedAt) {
        this.feedType = feedType;
        this.fileHash = fileHash;
        this.originalFilename = originalFilename;
        this.uploadedAt = uploadedAt;
    }

    /** Creates a feed with zero counts. The row tables need its ID before the file has been read. */
    public static Feed start(FeedType feedType, String fileHash, String originalFilename) {
        return new Feed(
                feedType, fileHash, originalFilename, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    public void recordCounts(int accepted, int rejected) {
        this.acceptedRows = accepted;
        this.rejectedRows = rejected;
        this.totalRows = accepted + rejected;
    }

    public boolean isFor(FeedType expectedType, String expectedHash) {
        return feedType == expectedType && fileHash.equals(expectedHash);
    }

    public Long getId() {
        return id;
    }

    public FeedType getFeedType() {
        return feedType;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getAcceptedRows() {
        return acceptedRows;
    }

    public int getRejectedRows() {
        return rejectedRows;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
