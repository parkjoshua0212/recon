package com.recon.reconciliation.repository;

import com.recon.reconciliation.domain.MatchRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class MatchRowRepository {

    // line_number order makes the first row for each txn_id the one that came first in the file.
    private static final String SELECT_FEED =
            """
            SELECT txn_id, account_id, asset, amount, status
            FROM feed_transaction
            WHERE feed_id = ?
            ORDER BY line_number
            """;

    private final JdbcTemplate jdbcTemplate;

    public MatchRowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, List<MatchRow>> loadGroupedByTxnId(long feedId) {
        Map<String, List<MatchRow>> rows = new HashMap<>();
        RowCallbackHandler collector = resultSet -> {
            MatchRow row = new MatchRow(
                    resultSet.getString("txn_id"),
                    resultSet.getString("account_id"),
                    resultSet.getString("asset"),
                    resultSet.getBigDecimal("amount"),
                    resultSet.getString("status"));
            // Most IDs appear once, so a one-element list keeps memory use down.
            rows.computeIfAbsent(row.txnId(), id -> new ArrayList<>(1)).add(row);
        };
        jdbcTemplate.query(SELECT_FEED, collector, feedId);
        return rows;
    }
}
