package com.recon.reconciliation.repository;

import com.recon.feed.domain.FeedType;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.ReconciliationBreak;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationBreakRepository {

    private static final int BATCH_SIZE = 1_000;

    private static final String INSERT =
            """
            INSERT INTO reconciliation_break
                (reconciliation_id, break_type, txn_id, feed_type, ledger_amount, external_amount,
                 ledger_status, external_status, ledger_asset, external_asset,
                 ledger_account_id, external_account_id, occurrences)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String COLUMNS =
            """
            SELECT break_type, txn_id, feed_type, ledger_amount, external_amount,
                   ledger_status, external_status, ledger_asset, external_asset,
                   ledger_account_id, external_account_id, occurrences
            FROM reconciliation_break
            """;

    private final JdbcTemplate jdbcTemplate;

    public ReconciliationBreakRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(long reconciliationId, List<ReconciliationBreak> breaks) {
        jdbcTemplate.batchUpdate(INSERT, breaks, BATCH_SIZE, (statement, item) -> {
            statement.setLong(1, reconciliationId);
            statement.setString(2, item.type().name());
            statement.setString(3, item.txnId());
            statement.setString(4, item.feedType() == null ? null : item.feedType().name());
            statement.setBigDecimal(5, item.ledgerAmount());
            statement.setBigDecimal(6, item.externalAmount());
            statement.setString(7, item.ledgerStatus());
            statement.setString(8, item.externalStatus());
            statement.setString(9, item.ledgerAsset());
            statement.setString(10, item.externalAsset());
            statement.setString(11, item.ledgerAccountId());
            statement.setString(12, item.externalAccountId());
            statement.setObject(13, item.occurrences(), Types.INTEGER);
        });
    }

    /** Includes every type, with zero where none were found, so clients get a stable shape. */
    public Map<BreakType, Long> countByType(long reconciliationId) {
        Map<BreakType, Long> counts = new EnumMap<>(BreakType.class);
        for (BreakType type : BreakType.values()) {
            counts.put(type, 0L);
        }
        jdbcTemplate.query(
                "SELECT break_type, COUNT(*) AS total FROM reconciliation_break "
                        + "WHERE reconciliation_id = ? GROUP BY break_type",
                resultSet -> {
                    counts.put(BreakType.valueOf(resultSet.getString("break_type")), resultSet.getLong("total"));
                },
                reconciliationId);
        return counts;
    }

    public List<ReconciliationBreak> findPage(long reconciliationId, int limit, long offset) {
        return jdbcTemplate.query(
                COLUMNS + " WHERE reconciliation_id = ? ORDER BY id LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> toBreak(resultSet),
                reconciliationId,
                limit,
                offset);
    }

    public List<ReconciliationBreak> findPageByType(long reconciliationId, BreakType type, int limit, long offset) {
        return jdbcTemplate.query(
                COLUMNS + " WHERE reconciliation_id = ? AND break_type = ? ORDER BY id LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> toBreak(resultSet),
                reconciliationId,
                type.name(),
                limit,
                offset);
    }

    private ReconciliationBreak toBreak(ResultSet resultSet) throws SQLException {
        String feedType = resultSet.getString("feed_type");
        return new ReconciliationBreak(
                BreakType.valueOf(resultSet.getString("break_type")),
                resultSet.getString("txn_id"),
                feedType == null ? null : FeedType.valueOf(feedType),
                resultSet.getBigDecimal("ledger_amount"),
                resultSet.getBigDecimal("external_amount"),
                resultSet.getString("ledger_status"),
                resultSet.getString("external_status"),
                resultSet.getString("ledger_asset"),
                resultSet.getString("external_asset"),
                resultSet.getString("ledger_account_id"),
                resultSet.getString("external_account_id"),
                resultSet.getObject("occurrences", Integer.class));
    }
}
