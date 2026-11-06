package com.recon.feed.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.recon.feed.domain.ParsedTransaction;
import com.recon.feed.domain.RejectedRow;
import com.recon.feed.domain.RowOutcome;
import com.recon.feed.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionRowValidatorTest {

    private static final String VALID_TIME = "2026-01-15T10:30:00Z";

    private final TransactionRowValidator validator = new TransactionRowValidator();

    @Test
    void acceptsValidRowAndKeepsExactAmount() {
        ParsedTransaction row = accepted(row("T-1", "ACC-1", "BTC", "0.123456789012345678", "SETTLED", VALID_TIME));

        assertThat(row.lineNumber()).isEqualTo(2);
        assertThat(row.txnId()).isEqualTo("T-1");
        assertThat(row.accountId()).isEqualTo("ACC-1");
        assertThat(row.asset()).isEqualTo("BTC");
        assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("0.123456789012345678"));
        assertThat(row.status()).isEqualTo(TransactionStatus.SETTLED);
    }

    @Test
    void trimsEveryField() {
        ParsedTransaction row = accepted(row(" T-1 ", " ACC-1 ", " BTC ", " 1.5 ", " SETTLED ", " " + VALID_TIME + " "));

        assertThat(row.txnId()).isEqualTo("T-1");
        assertThat(row.accountId()).isEqualTo("ACC-1");
        assertThat(row.asset()).isEqualTo("BTC");
        assertThat(row.amount()).isEqualByComparingTo("1.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0", "-0", "0.0", "-1.5", "+1.5",
        "12345678901234567890",
        "-12345678901234567890.123456789012345678",
        "000000000000000000000000001"
    })
    void acceptsAllowedAmounts(String amount) {
        assertThat(validate(row("T-1", "A", "BTC", amount, "SETTLED", VALID_TIME)))
                .isInstanceOf(ParsedTransaction.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+3", "1e3", "1.5E2", "abc", "1,5", "1.2.3", ".5", "5.", "--1", "NaN", "Infinity", "$5"})
    void rejectsNonPlainAmounts(String amount) {
        assertThat(rejected(row("T-1", "A", "BTC", amount, "SETTLED", VALID_TIME)).reason())
                .contains("amount must be a plain decimal number");
    }

    @Test
    void rejectsAmountWithTooManyIntegerDigits() {
        assertThat(rejected(row("T-1", "A", "BTC", "123456789012345678901", "SETTLED", VALID_TIME)).reason())
                .contains("more than 20 integer digits");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1234567890123456789", "1.0000000000000000000"})
    void rejectsAmountWithTooManyDecimalPlaces(String amount) {
        assertThat(rejected(row("T-1", "A", "BTC", amount, "SETTLED", VALID_TIME)).reason())
                .contains("more than 18 decimal places");
    }

    @ParameterizedTest
    @ValueSource(strings = {"settled", "Settled", "DONE", "PEND"})
    void rejectsUnknownOrLowercaseStatus(String status) {
        assertThat(rejected(row("T-1", "A", "BTC", "1", status, VALID_TIME)).reason())
                .contains("status must be");
    }

    @Test
    void convertsOffsetTimestampToUtc() {
        ParsedTransaction row = accepted(row("T-1", "A", "BTC", "1", "SETTLED", "2026-01-15T09:30:00+09:00"));

        assertThat(row.txnTimeUtc()).isEqualTo(LocalDateTime.of(2026, 1, 15, 0, 30));
    }

    @Test
    void convertsAcrossADateBoundaryAndKeepsMicroseconds() {
        ParsedTransaction row = accepted(row("T-1", "A", "BTC", "1", "SETTLED", "2026-01-01T00:30:00.123456+01:00"));

        assertThat(row.txnTimeUtc()).isEqualTo(LocalDateTime.of(2025, 12, 31, 23, 30, 0, 123_456_000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-15T10:30:00", "2026-01-15", "2026-01-15 10:30:00Z", "1768473000", "2026-13-01T00:00:00Z"})
    void rejectsTimestampWithoutOffsetOrInBadFormat(String timestamp) {
        assertThat(rejected(row("T-1", "A", "BTC", "1", "SETTLED", timestamp)).reason())
                .contains("timestamp must be ISO-8601");
    }

    @Test
    void rejectsTimestampMoreSpecificThanTheColumnCanStore() {
        assertThat(rejected(row("T-1", "A", "BTC", "1", "SETTLED", "2026-01-15T10:30:00.1234567Z")).reason())
                .contains("more precise than microseconds");
    }

    @Test
    void rejectsYearOutsideDatetimeRange() {
        assertThat(rejected(row("T-1", "A", "BTC", "1", "SETTLED", "0999-12-31T00:00:00Z")).reason())
                .contains("year must be between");
    }

    @Test
    void measuresColumnLimitsInCharactersRatherThanJavaChars() {
        String sixtyFourEmoji = "😀".repeat(64);

        assertThat(validate(row(sixtyFourEmoji, "A", "BTC", "1", "SETTLED", VALID_TIME)))
                .isInstanceOf(ParsedTransaction.class);
    }

    @Test
    void rejectsValuesLongerThanTheirColumns() {
        String reason = rejected(row("x".repeat(65), "y".repeat(65), "z".repeat(17), "1", "SETTLED", VALID_TIME)).reason();

        assertThat(reason)
                .contains("txn_id is longer than 64")
                .contains("account_id is longer than 64")
                .contains("asset is longer than 16");
    }

    @Test
    void acceptsValuesExactlyAtTheirColumnLimits() {
        assertThat(validate(row("x".repeat(64), "y".repeat(64), "z".repeat(16), "1", "SETTLED", VALID_TIME)))
                .isInstanceOf(ParsedTransaction.class);
    }

    @Test
    void reportsEveryProblemInARow() {
        String reason = rejected(row("", "", "", "", "", "")).reason();

        assertThat(reason)
                .contains("txn_id is required")
                .contains("account_id is required")
                .contains("asset is required")
                .contains("amount is required")
                .contains("status is required")
                .contains("timestamp is required");
    }

    @Test
    void rejectsWrongColumnCount() {
        assertThat(rejected(List.of("T-1", "A")).reason()).contains("expected 6 columns but found 2");
    }

    private List<String> row(String... fields) {
        return List.of(fields);
    }

    private RowOutcome validate(List<String> fields) {
        return validator.validate(2, String.join(",", fields), fields);
    }

    private ParsedTransaction accepted(List<String> fields) {
        return (ParsedTransaction) validate(fields);
    }

    private RejectedRow rejected(List<String> fields) {
        return (RejectedRow) validate(fields);
    }
}
