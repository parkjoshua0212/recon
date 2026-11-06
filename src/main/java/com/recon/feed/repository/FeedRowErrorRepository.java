package com.recon.feed.repository;

import com.recon.feed.domain.RejectedRow;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedRowErrorRepository {

    private static final String INSERT =
            "INSERT INTO feed_row_error (feed_id, line_number, raw_line, reason) VALUES (?, ?, ?, ?)";

    private static final String SELECT_PAGE =
            """
            SELECT line_number, raw_line, reason
            FROM feed_row_error
            WHERE feed_id = ?
            ORDER BY line_number, id
            LIMIT ? OFFSET ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public FeedRowErrorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(long feedId, List<RejectedRow> rows) {
        jdbcTemplate.batchUpdate(INSERT, rows, rows.size(), (statement, row) -> {
            statement.setLong(1, feedId);
            statement.setInt(2, row.lineNumber());
            statement.setString(3, row.rawLine());
            statement.setString(4, row.reason());
        });
    }

    public List<RejectedRow> findPage(long feedId, int limit, long offset) {
        return jdbcTemplate.query(
                SELECT_PAGE,
                (resultSet, rowNumber) -> new RejectedRow(
                        resultSet.getInt("line_number"),
                        resultSet.getString("raw_line"),
                        resultSet.getString("reason")),
                feedId,
                limit,
                offset);
    }
}
