package com.recon.feed.repository;

import com.recon.feed.domain.ParsedTransaction;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Uses JdbcTemplate rather than JPA because Hibernate turns off insert batching for auto-increment
 * IDs: it would send one INSERT per row.
 */
@Repository
public class FeedTransactionRepository {

    private static final String INSERT =
            """
            INSERT INTO feed_transaction
                (feed_id, line_number, txn_id, account_id, asset, amount, status, txn_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public FeedTransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(long feedId, List<ParsedTransaction> rows) {
        jdbcTemplate.batchUpdate(INSERT, rows, rows.size(), (statement, row) -> {
            statement.setLong(1, feedId);
            statement.setInt(2, row.lineNumber());
            statement.setString(3, row.txnId());
            statement.setString(4, row.accountId());
            statement.setString(5, row.asset());
            statement.setBigDecimal(6, row.amount());
            statement.setString(7, row.status().name());
            // LocalDateTime is written as-is, so the driver cannot shift it to another time zone.
            statement.setObject(8, row.txnTimeUtc());
        });
    }
}
