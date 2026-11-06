package com.recon.feed.service;

import com.recon.common.InvalidFileException;
import com.recon.common.NotFoundException;
import com.recon.common.PageResponse;
import com.recon.feed.domain.Feed;
import com.recon.feed.domain.FeedType;
import com.recon.feed.dto.FeedSummaryResponse;
import com.recon.feed.dto.FeedUploadResult;
import com.recon.feed.dto.RowErrorResponse;
import com.recon.feed.repository.FeedRepository;
import com.recon.feed.repository.FeedRowErrorRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FeedService {

    private final FeedRepository feeds;
    private final FeedRowErrorRepository rowErrors;
    private final FeedWriter writer;
    private final FeedIdempotencyCache idempotencyCache;
    private final FileHasher hasher;

    public FeedService(
            FeedRepository feeds,
            FeedRowErrorRepository rowErrors,
            FeedWriter writer,
            FeedIdempotencyCache idempotencyCache,
            FileHasher hasher) {
        this.feeds = feeds;
        this.rowErrors = rowErrors;
        this.writer = writer;
        this.idempotencyCache = idempotencyCache;
        this.hasher = hasher;
    }

    /** Deliberately not @Transactional: Redis must only be written once writer.ingest has committed. */
    public FeedUploadResult upload(FeedType feedType, MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidFileException("File is empty");
        }
        String fileHash = hasher.sha256(file);

        Optional<Feed> existing = findExisting(feedType, fileHash);
        if (existing.isPresent()) {
            return new FeedUploadResult(FeedSummaryResponse.from(existing.get()), false);
        }

        try {
            Feed created = writer.ingest(feedType, fileHash, file);
            idempotencyCache.remember(feedType, fileHash, created.getId());
            return new FeedUploadResult(FeedSummaryResponse.from(created), true);
        } catch (DataIntegrityViolationException ex) {
            // Another upload of this file committed first. If nothing is found, this was some other
            // constraint failing, so the original error is rethrown.
            Feed winner = feeds.findByFeedTypeAndFileHash(feedType, fileHash).orElseThrow(() -> ex);
            idempotencyCache.remember(feedType, fileHash, winner.getId());
            return new FeedUploadResult(FeedSummaryResponse.from(winner), false);
        }
    }

    public FeedSummaryResponse getSummary(long feedId) {
        return FeedSummaryResponse.from(requireFeed(feedId));
    }

    public PageResponse<RowErrorResponse> getErrors(long feedId, int page, int size) {
        Feed feed = requireFeed(feedId);
        long offset = (long) page * size;
        List<RowErrorResponse> items = rowErrors.findPage(feedId, size, offset).stream()
                .map(RowErrorResponse::from)
                .toList();
        // rejected_rows already holds the total, which saves a COUNT query on every page.
        return PageResponse.of(items, page, size, feed.getRejectedRows());
    }

    /** Redis first, then the database. The database answer refills Redis for next time. */
    private Optional<Feed> findExisting(FeedType feedType, String fileHash) {
        return idempotencyCache
                .find(feedType, fileHash)
                .flatMap(feeds::findById)
                // A key left over from a wiped database could point at an unrelated feed.
                .filter(feed -> feed.isFor(feedType, fileHash))
                .or(() -> {
                    Optional<Feed> stored = feeds.findByFeedTypeAndFileHash(feedType, fileHash);
                    stored.ifPresent(feed ->
                            idempotencyCache.remember(feedType, fileHash, feed.getId()));
                    return stored;
                });
    }

    private Feed requireFeed(long feedId) {
        return feeds.findById(feedId)
                .orElseThrow(() -> new NotFoundException("Feed " + feedId + " not found"));
    }
}
