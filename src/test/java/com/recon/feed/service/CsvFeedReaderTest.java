package com.recon.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recon.common.InvalidFileException;
import com.recon.feed.domain.ParsedTransaction;
import com.recon.feed.domain.RejectedRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvFeedReaderTest {

    private static final String HEADER = "txn_id,account_id,asset,amount,status,timestamp\n";
    private static final String GOOD_ROW = "T-1,ACC-1,BTC,1.5,SETTLED,2026-01-15T10:30:00Z\n";

    private final CsvFeedReader reader = new CsvFeedReader(new TransactionRowValidator());
    private final CollectingSink sink = new CollectingSink();

    @Test
    void separatesGoodRowsFromBadRowsAndKeepsPhysicalLineNumbers() {
        read(HEADER + GOOD_ROW + "T-2,ACC-1,BTC,bad,SETTLED,2026-01-15T10:30:00Z\n" + "T-3,ACC-1,BTC,2,PENDING,2026-01-15T10:30:00Z\n");

        assertThat(sink.accepted).extracting(ParsedTransaction::lineNumber).containsExactly(2, 4);
        assertThat(sink.rejected).extracting(RejectedRow::lineNumber).containsExactly(3);
        assertThat(sink.rejected.get(0).rawLine()).isEqualTo("T-2,ACC-1,BTC,bad,SETTLED,2026-01-15T10:30:00Z");
    }

    @Test
    void keepsDuplicateRowsBecauseMatchingMustReportThem() {
        read(HEADER + GOOD_ROW + GOOD_ROW);

        assertThat(sink.accepted).hasSize(2);
    }

    @Test
    void handlesExcelByteOrderMark() {
        read("\uFEFF" + HEADER + GOOD_ROW);

        assertThat(sink.accepted).hasSize(1);
    }

    @Test
    void handlesWindowsLineEndings() {
        read((HEADER + GOOD_ROW).replace("\n", "\r\n"));

        assertThat(sink.accepted).hasSize(1);
        assertThat(sink.accepted.get(0).txnTimeUtc()).isNotNull();
    }

    @Test
    void readsQuotedFieldsContainingCommas() {
        read(HEADER + "\"T,1\",ACC-1,BTC,1,SETTLED,2026-01-15T10:30:00Z\n");

        assertThat(sink.accepted.get(0).txnId()).isEqualTo("T,1");
    }

    @Test
    void reportsBlankLinesInsteadOfDroppingThem() {
        read(HEADER + GOOD_ROW + "\n   \n" + GOOD_ROW);

        assertThat(sink.accepted).hasSize(2);
        assertThat(sink.rejected).extracting(RejectedRow::lineNumber).containsExactly(3, 4);
        assertThat(sink.rejected).extracting(RejectedRow::reason).containsOnly("line is blank");
    }

    @Test
    void reportsUnbalancedQuoteAsARowProblem() {
        read(HEADER + "\"T-1,ACC-1,BTC,1,SETTLED,2026-01-15T10:30:00Z\n");

        assertThat(sink.rejected).extracting(RejectedRow::reason).containsExactly("line is not valid CSV");
    }

    @Test
    void limitsRawLineWithoutSplittingACharacter() {
        String longLine = "😀".repeat(CsvFeedReader.MAX_RAW_LINE_LENGTH + 10);

        read(HEADER + longLine + "\n");

        String stored = sink.rejected.get(0).rawLine();
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(CsvFeedReader.MAX_RAW_LINE_LENGTH);
        assertThat(stored).endsWith("😀");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> read("")).isInstanceOf(InvalidFileException.class).hasMessage("File is empty");
    }

    @Test
    void rejectsHeaderWithNoRows() {
        assertThatThrownBy(() -> read(HEADER)).isInstanceOf(InvalidFileException.class).hasMessageContaining("no data rows");
    }

    @Test
    void rejectsWrongHeader() {
        assertThatThrownBy(() -> read("id,account,asset,amount,status,time\n" + GOOD_ROW))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Header must be exactly");
    }

    @Test
    void rejectsReorderedHeader() {
        assertThatThrownBy(() -> read("account_id,txn_id,asset,amount,status,timestamp\n" + GOOD_ROW))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    void rejectsBytesThatAreNotUtf8() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes((HEADER + GOOD_ROW).getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(bytes.toByteArray()), sink))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("UTF-8");
    }

    private void read(String content) {
        reader.read(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), sink);
    }

    private static final class CollectingSink implements FeedRowSink {

        private final List<ParsedTransaction> accepted = new ArrayList<>();
        private final List<RejectedRow> rejected = new ArrayList<>();

        @Override
        public void accept(ParsedTransaction transaction) {
            accepted.add(transaction);
        }

        @Override
        public void reject(RejectedRow row) {
            rejected.add(row);
        }
    }
}
