package com.recon.feed.service;

import com.recon.feed.domain.ParsedTransaction;
import com.recon.feed.domain.RejectedRow;

/** Receives rows as they are read, so a 50 MB file never has to fit in memory. */
public interface FeedRowSink {

    void accept(ParsedTransaction transaction);

    void reject(RejectedRow row);
}
