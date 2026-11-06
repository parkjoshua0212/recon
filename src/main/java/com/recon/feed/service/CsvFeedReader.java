package com.recon.feed.service;

import com.recon.common.InvalidFileException;
import com.recon.feed.domain.ParsedTransaction;
import com.recon.feed.domain.RejectedRow;
import com.recon.feed.domain.RowOutcome;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Reads the file one physical line at a time. feed_row_error needs the exact raw line and its line
 * number, which a whole-file CSV parser does not expose, so Commons CSV parses each line separately.
 * A quoted field therefore cannot span lines, and such a row is reported as malformed.
 */
@Component
public class CsvFeedReader {

    static final List<String> HEADER =
            List.of("txn_id", "account_id", "asset", "amount", "status", "timestamp");

    // TEXT holds 65,535 bytes and a character takes at most 4, so this always fits.
    static final int MAX_RAW_LINE_LENGTH = 4_000;

    private static final char BYTE_ORDER_MARK = '﻿';

    private final TransactionRowValidator validator;

    public CsvFeedReader(TransactionRowValidator validator) {
        this.validator = validator;
    }

    public void read(InputStream input, FeedRowSink sink) {
        // REPORT makes bad bytes an error. The default would silently replace them.
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            checkHeader(reader.readLine());

            int lineNumber = 1;
            int dataRows = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                dataRows++;
                dispatch(readRow(lineNumber, line), sink);
            }
            if (dataRows == 0) {
                throw new InvalidFileException("File has a header but no data rows");
            }
        } catch (CharacterCodingException ex) {
            throw new InvalidFileException("File is not valid UTF-8");
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read uploaded file", ex);
        }
    }

    private void checkHeader(String firstLine) {
        if (firstLine == null) {
            throw new InvalidFileException("File is empty");
        }
        // Excel puts a byte order mark before the first column when it saves CSV as UTF-8.
        String line = firstLine.startsWith(String.valueOf(BYTE_ORDER_MARK))
                ? firstLine.substring(1)
                : firstLine;

        List<String> columns;
        try {
            columns = parseFields(line).stream().map(String::strip).toList();
        } catch (IOException | RuntimeException ex) {
            columns = List.of();
        }
        if (!HEADER.equals(columns)) {
            throw new InvalidFileException("Header must be exactly: " + String.join(",", HEADER));
        }
    }

    private RowOutcome readRow(int lineNumber, String line) {
        String rawLine = Text.truncate(line, MAX_RAW_LINE_LENGTH);
        if (line.isBlank()) {
            return new RejectedRow(lineNumber, rawLine, "line is blank");
        }
        List<String> fields;
        try {
            fields = parseFields(line);
        } catch (IOException | RuntimeException ex) {
            return new RejectedRow(lineNumber, rawLine, "line is not valid CSV");
        }
        return validator.validate(lineNumber, rawLine, fields);
    }

    private List<String> parseFields(String line) throws IOException {
        try (CSVParser parser = CSVParser.parse(line, CSVFormat.DEFAULT)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.size() != 1) {
                throw new IOException("Expected one record");
            }
            List<String> fields = new ArrayList<>(records.get(0).size());
            records.get(0).forEach(fields::add);
            return fields;
        }
    }

    private void dispatch(RowOutcome outcome, FeedRowSink sink) {
        switch (outcome) {
            case ParsedTransaction transaction -> sink.accept(transaction);
            case RejectedRow rejected -> sink.reject(rejected);
        }
    }
}
