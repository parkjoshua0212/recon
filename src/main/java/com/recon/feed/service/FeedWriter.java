package com.recon.feed.service;

import com.recon.feed.domain.Feed;
import com.recon.feed.domain.FeedType;
import com.recon.feed.repository.FeedRepository;
import com.recon.feed.repository.FeedRowErrorRepository;
import com.recon.feed.repository.FeedTransactionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores one upload in a single transaction. This is a separate bean from FeedService because
 * Spring applies @Transactional through a proxy, which a call inside the same class would bypass.
 * The separation also means the transaction has committed by the time FeedService writes to Redis.
 */
@Service
public class FeedWriter {

    private static final int MAX_FILENAME_LENGTH = 255;

    private final FeedRepository feeds;
    private final FeedTransactionRepository transactions;
    private final FeedRowErrorRepository rowErrors;
    private final CsvFeedReader reader;

    public FeedWriter(
            FeedRepository feeds,
            FeedTransactionRepository transactions,
            FeedRowErrorRepository rowErrors,
            CsvFeedReader reader) {
        this.feeds = feeds;
        this.transactions = transactions;
        this.rowErrors = rowErrors;
        this.reader = reader;
    }

    /**
     * The feed row goes in first because the row tables reference its ID. The UNIQUE constraint also
     * fires here, so a racing duplicate fails before it does any row work.
     */
    @Transactional
    public Feed ingest(FeedType feedType, String fileHash, MultipartFile file) {
        Feed feed = feeds.saveAndFlush(Feed.start(feedType, fileHash, filenameOf(file)));
        BatchingRowSink sink = new BatchingRowSink(feed.getId(), transactions, rowErrors);

        try (InputStream input = file.getInputStream()) {
            reader.read(input, sink);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not open uploaded file", ex);
        }

        sink.flush();
        feed.recordCounts(sink.accepted(), sink.rejected());
        return feed;
    }

    private String filenameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null ? null : Text.truncate(name, MAX_FILENAME_LENGTH);
    }
}
