package com.recon.feed.repository;

import com.recon.feed.domain.Feed;
import com.recon.feed.domain.FeedType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedRepository extends JpaRepository<Feed, Long> {

    Optional<Feed> findByFeedTypeAndFileHash(FeedType feedType, String fileHash);
}
