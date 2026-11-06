package com.recon.feed.service;

import com.recon.feed.domain.ParsedTransaction;
import com.recon.feed.domain.RejectedRow;
import com.recon.feed.domain.RowOutcome;
import com.recon.feed.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks one row against everything the database would refuse or silently change. A row that passes
 * here cannot fail its INSERT, so one bad value can never abort a whole batch.
 */
@Component
public class TransactionRowValidator {

    static final int COLUMN_COUNT = 6;
    static final int MAX_REASON_LENGTH = 512;

    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_ASSET_LENGTH = 16;
    private static final int MAX_INTEGER_DIGITS = 20;
    private static final int MAX_DECIMAL_PLACES = 18;
    private static final int MIN_DATETIME_YEAR = 1000;
    private static final int MAX_DATETIME_YEAR = 9999;

    // Plain digits only, which is what rules out scientific notation such as 1E+3.
    private static final Pattern AMOUNT = Pattern.compile("[+-]?(\\d+)(?:\\.(\\d+))?");

    public RowOutcome validate(int lineNumber, String rawLine, List<String> fields) {
        if (fields.size() != COLUMN_COUNT) {
            String reason = "expected " + COLUMN_COUNT + " columns but found " + fields.size();
            return new RejectedRow(lineNumber, rawLine, reason);
        }

        List<String> problems = new ArrayList<>();
        String txnId = requireText("txn_id", fields.get(0), MAX_ID_LENGTH, problems);
        String accountId = requireText("account_id", fields.get(1), MAX_ID_LENGTH, problems);
        String asset = requireText("asset", fields.get(2), MAX_ASSET_LENGTH, problems);
        BigDecimal amount = parseAmount(fields.get(3).strip(), problems);
        TransactionStatus status = parseStatus(fields.get(4).strip(), problems);
        LocalDateTime txnTime = parseTimestamp(fields.get(5).strip(), problems);

        if (!problems.isEmpty()) {
            String reason = Text.truncate(String.join("; ", problems), MAX_REASON_LENGTH);
            return new RejectedRow(lineNumber, rawLine, reason);
        }
        return new ParsedTransaction(lineNumber, txnId, accountId, asset, amount, status, txnTime);
    }

    private String requireText(String column, String value, int maxLength, List<String> problems) {
        String text = value.strip();
        if (text.isEmpty()) {
            problems.add(column + " is required");
        } else if (Text.length(text) > maxLength) {
            problems.add(column + " is longer than " + maxLength + " characters");
        }
        return text;
    }

    private BigDecimal parseAmount(String text, List<String> problems) {
        if (text.isEmpty()) {
            problems.add("amount is required");
            return null;
        }
        Matcher matcher = AMOUNT.matcher(text);
        if (!matcher.matches()) {
            problems.add("amount must be a plain decimal number");
            return null;
        }
        // Digits are counted from the text so that 1.0000000000000000000 is still rejected.
        String integerDigits = matcher.group(1).replaceFirst("^0+", "");
        String decimalDigits = matcher.group(2) == null ? "" : matcher.group(2);
        boolean valid = true;
        if (integerDigits.length() > MAX_INTEGER_DIGITS) {
            problems.add("amount has more than " + MAX_INTEGER_DIGITS + " integer digits");
            valid = false;
        }
        if (decimalDigits.length() > MAX_DECIMAL_PLACES) {
            problems.add("amount has more than " + MAX_DECIMAL_PLACES + " decimal places");
            valid = false;
        }
        return valid ? new BigDecimal(text) : null;
    }

    private TransactionStatus parseStatus(String text, List<String> problems) {
        if (text.isEmpty()) {
            problems.add("status is required");
            return null;
        }
        try {
            // valueOf is case-sensitive, so lowercase values are rejected.
            return TransactionStatus.valueOf(text);
        } catch (IllegalArgumentException ex) {
            problems.add("status must be PENDING, SETTLED or FAILED");
            return null;
        }
    }

    private LocalDateTime parseTimestamp(String text, List<String> problems) {
        if (text.isEmpty()) {
            problems.add("timestamp is required");
            return null;
        }
        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ex) {
            problems.add("timestamp must be ISO-8601 with an offset, such as 2026-01-15T10:30:00Z");
            return null;
        }
        // DATETIME(6) would round anything finer than microseconds without any error.
        if (parsed.getNano() % 1_000 != 0) {
            problems.add("timestamp is more precise than microseconds");
            return null;
        }
        LocalDateTime utc = parsed.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if (utc.getYear() < MIN_DATETIME_YEAR || utc.getYear() > MAX_DATETIME_YEAR) {
            problems.add("timestamp year must be between 1000 and 9999");
            return null;
        }
        return utc;
    }
}
