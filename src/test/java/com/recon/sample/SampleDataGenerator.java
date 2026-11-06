package com.recon.sample;

import com.recon.feed.domain.FeedType;
import com.recon.reconciliation.domain.BreakType;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Builds a matching pair of CSV files plus the exact list of breaks planted in them. A fixed seed
 * always gives the same files, so a failure can be reproduced.
 *
 * <p>Clean rows are deliberately awkward: the external side writes amounts with extra zeros, uses a
 * +09:00 offset, and is a couple of seconds later. None of that is a break, so a false positive here
 * fails the test.
 *
 * <p>Run it with {@code ./gradlew generateSamples}. The files land in build/samples.
 */
public final class SampleDataGenerator {

    public record ExpectedBreak(BreakType type, String txnId, FeedType feedType) {}

    public record SampleData(String ledgerCsv, String externalCsv, List<ExpectedBreak> expectedBreaks) {}

    private record Row(String txnId, String accountId, String asset, BigDecimal amount, String status, OffsetDateTime time) {

        Row withAmount(BigDecimal value) {
            return new Row(txnId, accountId, asset, value, status, time);
        }

        Row withStatus(String value) {
            return new Row(txnId, accountId, asset, amount, value, time);
        }

        Row withAsset(String value) {
            return new Row(txnId, accountId, value, amount, status, time);
        }

        Row withAccount(String value) {
            return new Row(txnId, value, asset, amount, status, time);
        }
    }

    private static final String HEADER = "txn_id,account_id,asset,amount,status,timestamp\n";
    private static final int MAX_DECIMAL_PLACES = 18;
    private static final List<String> ASSETS = List.of("BTC", "ETH", "USDT", "SOL");
    private static final OffsetDateTime START = OffsetDateTime.of(2026, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC);

    private final Random random;
    private final String prefix;
    private final List<Row> ledger = new ArrayList<>();
    private final List<Row> external = new ArrayList<>();
    private final List<ExpectedBreak> expected = new ArrayList<>();
    private int sequence;

    private SampleDataGenerator(long seed, String prefix) {
        this.random = new Random(seed);
        this.prefix = prefix;
    }

    public static SampleData generate(long seed, String prefix, int cleanRows, int plantedPerType) {
        return new SampleDataGenerator(seed, prefix).build(cleanRows, plantedPerType);
    }

    private SampleData build(int cleanRows, int plantedPerType) {
        for (int i = 0; i < cleanRows; i++) {
            addPair(row("CLEAN"));
        }
        for (int i = 0; i < plantedPerType; i++) {
            plantOneOfEachType();
        }
        if (plantedPerType > 0) {
            plantCombinations();
        }
        // Row order must not matter, so the external file gets a different one.
        Collections.shuffle(external, random);
        return new SampleData(render(ledger, false), render(external, true), List.copyOf(expected));
    }

    private void plantOneOfEachType() {
        Row missingExternal = row("MISSING-EXT");
        ledger.add(missingExternal);
        expect(BreakType.MISSING_EXTERNAL, missingExternal, null);

        Row missingInternal = row("MISSING-INT");
        external.add(missingInternal);
        expect(BreakType.MISSING_INTERNAL, missingInternal, null);

        Row amount = row("AMOUNT");
        // The smallest difference a column of this size can hold.
        ledger.add(amount);
        external.add(amount.withAmount(amount.amount().add(new BigDecimal("0.000000000000000001"))));
        expect(BreakType.AMOUNT_MISMATCH, amount, null);

        Row status = row("STATUS");
        ledger.add(status.withStatus("PENDING"));
        external.add(status.withStatus("SETTLED"));
        expect(BreakType.STATUS_MISMATCH, status, null);

        Row asset = row("ASSET");
        ledger.add(asset.withAsset("BTC"));
        external.add(asset.withAsset("ETH"));
        expect(BreakType.ASSET_MISMATCH, asset, null);

        Row account = row("ACCOUNT");
        ledger.add(account);
        external.add(account.withAccount(account.accountId() + "-X"));
        expect(BreakType.ACCOUNT_MISMATCH, account, null);

        Row ledgerDuplicate = row("DUP-LEDGER");
        ledger.add(ledgerDuplicate);
        ledger.add(ledgerDuplicate);
        external.add(ledgerDuplicate);
        expect(BreakType.DUPLICATE, ledgerDuplicate, FeedType.LEDGER);

        Row externalDuplicate = row("DUP-EXTERNAL");
        ledger.add(externalDuplicate);
        external.add(externalDuplicate);
        external.add(externalDuplicate);
        expect(BreakType.DUPLICATE, externalDuplicate, FeedType.EXTERNAL);
    }

    /** Cases where one transaction must produce more than one break. */
    private void plantCombinations() {
        Row amountAndStatus = row("COMBO-AMOUNT-STATUS");
        ledger.add(amountAndStatus.withStatus("PENDING"));
        external.add(amountAndStatus.withStatus("FAILED").withAmount(amountAndStatus.amount().negate()));
        expect(BreakType.AMOUNT_MISMATCH, amountAndStatus, null);
        expect(BreakType.STATUS_MISMATCH, amountAndStatus, null);

        Row everything = row("COMBO-ALL-FIELDS");
        ledger.add(everything.withAsset("BTC").withStatus("PENDING"));
        external.add(everything
                .withAsset("ETH")
                .withStatus("SETTLED")
                .withAccount(everything.accountId() + "-X")
                .withAmount(everything.amount().add(BigDecimal.ONE)));
        expect(BreakType.ASSET_MISMATCH, everything, null);
        expect(BreakType.ACCOUNT_MISMATCH, everything, null);
        expect(BreakType.AMOUNT_MISMATCH, everything, null);
        expect(BreakType.STATUS_MISMATCH, everything, null);

        Row duplicateAndMissing = row("COMBO-DUP-MISSING");
        ledger.add(duplicateAndMissing);
        ledger.add(duplicateAndMissing);
        expect(BreakType.DUPLICATE, duplicateAndMissing, FeedType.LEDGER);
        expect(BreakType.MISSING_EXTERNAL, duplicateAndMissing, null);

        Row duplicateBothSides = row("COMBO-DUP-BOTH");
        ledger.add(duplicateBothSides);
        ledger.add(duplicateBothSides);
        external.add(duplicateBothSides);
        external.add(duplicateBothSides);
        expect(BreakType.DUPLICATE, duplicateBothSides, FeedType.LEDGER);
        expect(BreakType.DUPLICATE, duplicateBothSides, FeedType.EXTERNAL);

        Row duplicateAndAmount = row("COMBO-DUP-AMOUNT");
        ledger.add(duplicateAndAmount);
        ledger.add(duplicateAndAmount);
        external.add(duplicateAndAmount.withAmount(duplicateAndAmount.amount().add(BigDecimal.ONE)));
        expect(BreakType.DUPLICATE, duplicateAndAmount, FeedType.LEDGER);
        expect(BreakType.AMOUNT_MISMATCH, duplicateAndAmount, null);
    }

    private void addPair(Row row) {
        ledger.add(row);
        external.add(row);
    }

    private void expect(BreakType type, Row row, FeedType feedType) {
        expected.add(new ExpectedBreak(type, row.txnId(), feedType));
    }

    private Row row(String label) {
        sequence++;
        BigDecimal amount = BigDecimal.valueOf(random.nextLong(1, 1_000_000_000L), 6).setScale(6, RoundingMode.UNNECESSARY);
        if (random.nextInt(10) == 0) {
            amount = amount.negate();
        }
        return new Row(
                "%s-%s-%06d".formatted(prefix, label, sequence),
                "ACC-" + random.nextInt(50),
                ASSETS.get(random.nextInt(ASSETS.size())),
                amount,
                "SETTLED",
                START.plusSeconds(sequence));
    }

    private String render(List<Row> rows, boolean externalStyle) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (Row row : rows) {
            OffsetDateTime time = externalStyle
                    ? row.time().plusSeconds(2).withOffsetSameInstant(ZoneOffset.ofHours(9))
                    : row.time();
            String amount = externalStyle ? withExtraZeros(row.amount()) : row.amount().toPlainString();
            csv.append(String.join(",", row.txnId(), row.accountId(), row.asset(), amount, row.status(), time.toString()))
                    .append('\n');
        }
        return csv.toString();
    }

    /** Writes the same value with more digits, staying within the 18 decimal places feeds allow. */
    private String withExtraZeros(BigDecimal amount) {
        return amount.setScale(Math.min(amount.scale() + 3, MAX_DECIMAL_PLACES), RoundingMode.UNNECESSARY).toPlainString();
    }

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args.length > 0 ? args[0] : "build/samples");
        Files.createDirectories(directory);
        SampleData data = generate(42L, "GEN", 1_000, 10);

        Files.writeString(directory.resolve("generated-ledger.csv"), data.ledgerCsv());
        Files.writeString(directory.resolve("generated-external.csv"), data.externalCsv());

        StringBuilder expected = new StringBuilder("break_type,txn_id,feed_type\n");
        for (ExpectedBreak item : data.expectedBreaks()) {
            expected.append(item.type()).append(',').append(item.txnId()).append(',')
                    .append(item.feedType() == null ? "" : item.feedType()).append('\n');
        }
        Files.writeString(directory.resolve("expected-breaks.csv"), expected.toString());

        System.out.println("Wrote " + data.expectedBreaks().size() + " planted breaks to " + directory.toAbsolutePath());
    }
}
