package com.recon.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.recon.TestcontainersConfiguration;
import com.recon.feed.domain.FeedType;
import com.recon.reconciliation.domain.BreakType;
import com.recon.reconciliation.domain.ReconciliationBreak;
import com.recon.reconciliation.repository.MatchRowRepository;
import com.recon.reconciliation.repository.ReconciliationBreakRepository;
import com.recon.reconciliation.service.MatchingEngine;
import com.recon.sample.SampleDataGenerator;
import com.recon.sample.SampleDataGenerator.ExpectedBreak;
import com.recon.sample.SampleDataGenerator.SampleData;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReconciliationIntegrationTest {

    private static final String HEADER = "txn_id,account_id,asset,amount,status,timestamp\n";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MatchRowRepository matchRows;

    @Autowired
    private ReconciliationBreakRepository breakRepository;

    @Value("${recon.matching.chunks}")
    private int chunks;

    @MockitoSpyBean
    private MatchingEngine engine;

    /** The core guarantee: every planted break is found, and nothing else is reported. */
    @Test
    void findsExactlyThePlantedBreaks() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 5_000, 25);

        JsonNode summary = reconcile(data).getBody();

        assertThat(summary.get("status").asText()).isEqualTo("COMPLETED");
        List<ExpectedBreak> found = fetchAllBreaks(summary.get("id").asLong());
        assertThat(found).containsExactlyInAnyOrderElementsOf(data.expectedBreaks());
    }

    /** The stored result of a parallel run, field by field and in order, against the plain engine. */
    @Test
    void parallelRunStoresExactlyWhatTheSingleThreadedEngineProduces() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 3_000, 40);
        long ledger = uploadFeed("ledger", data.ledgerCsv());
        long external = uploadFeed("external", data.externalCsv());

        long id = start(ledger, external).getBody().get("id").asLong();

        List<ReconciliationBreak> singleThreaded =
                new MatchingEngine().match(matchRows.loadGroupedByTxnId(ledger), matchRows.loadGroupedByTxnId(external));
        assertThat(breakRepository.findPage(id, singleThreaded.size() + 1, 0)).containsExactlyElementsOf(singleThreaded);
    }

    @Test
    void reconciliationRunsThroughTheParallelEngine() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 20, 1);
        long ledger = uploadFeed("ledger", data.ledgerCsv());
        long external = uploadFeed("external", data.externalCsv());
        clearInvocations(engine);

        start(ledger, external);

        verify(engine, times(chunks)).match(any(), any());
    }

    @Test
    void findsThePlantedBreaksForManySeeds() {
        for (long seed = 1; seed <= 5; seed++) {
            SampleData data = SampleDataGenerator.generate(seed, uniquePrefix(), 200, 3);

            long id = reconcile(data).getBody().get("id").asLong();

            assertThat(fetchAllBreaks(id)).containsExactlyInAnyOrderElementsOf(data.expectedBreaks());
        }
    }

    @Test
    void summaryCountsEachBreakTypeAndReportsDuration() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 100, 4);
        Map<BreakType, Long> expected = data.expectedBreaks().stream()
                .collect(Collectors.groupingBy(ExpectedBreak::type, Collectors.counting()));

        ResponseEntity<JsonNode> created = reconcile(data);
        JsonNode body = created.getBody();

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/reconciliations/" + body.get("id").asLong());
        assertThat(body.get("totalBreaks").asLong()).isEqualTo(data.expectedBreaks().size());
        assertThat(body.get("durationMillis").asLong()).isNotNegative();
        assertThat(body.get("startedAt").asText()).isNotBlank();
        assertThat(body.get("finishedAt").asText()).isNotBlank();
        for (BreakType type : BreakType.values()) {
            assertThat(body.at("/breakCounts/" + type).asLong()).isEqualTo(expected.get(type));
        }

        JsonNode fetched = rest.getForObject("/api/reconciliations/" + body.get("id").asLong(), JsonNode.class);
        assertThat(fetched).isEqualTo(body);
    }

    @Test
    void cleanFeedsCompleteWithZeroBreaksAndAllTypesListed() {
        JsonNode body = reconcile(SampleDataGenerator.generate(42L, uniquePrefix(), 20, 0).ledgerCsv()).getBody();

        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(body.get("totalBreaks").asLong()).isZero();
        assertThat(body.get("breakCounts")).hasSize(BreakType.values().length);
    }

    @Test
    void breaksFilterByTypeWithCorrectTotals() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 50, 3);
        long id = reconcile(data).getBody().get("id").asLong();
        long expected = data.expectedBreaks().stream().filter(b -> b.type() == BreakType.AMOUNT_MISMATCH).count();

        JsonNode page = rest.getForObject(
                "/api/reconciliations/" + id + "/breaks?type=AMOUNT_MISMATCH&size=200", JsonNode.class);

        assertThat(page.get("totalItems").asLong()).isEqualTo(expected);
        assertThat(page.get("items")).hasSize((int) expected);
        page.get("items").forEach(item -> assertThat(item.get("type").asText()).isEqualTo("AMOUNT_MISMATCH"));
    }

    @Test
    void breaksArePagedWithoutOverlap() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 10, 3);
        long id = reconcile(data).getBody().get("id").asLong();

        JsonNode first = rest.getForObject("/api/reconciliations/" + id + "/breaks?page=0&size=5", JsonNode.class);
        JsonNode second = rest.getForObject("/api/reconciliations/" + id + "/breaks?page=1&size=5", JsonNode.class);

        assertThat(first.get("items")).hasSize(5);
        assertThat(first.get("totalItems").asLong()).isEqualTo(data.expectedBreaks().size());
        assertThat(first.get("items").get(0)).isNotEqualTo(second.get("items").get(0));
        assertThat(second.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void breakShowsBothSidesWithExactAmountsAsStrings() {
        String txnId = uniquePrefix();
        String ledger = HEADER + txnId + ",ACC-1,BTC,0.100000000000000001,SETTLED,2026-01-15T00:00:00Z\n";
        String external = HEADER + txnId + ",ACC-1,BTC,0.100000000000000002,SETTLED,2026-01-15T00:00:00Z\n";
        long id = reconcile(ledger, external).getBody().get("id").asLong();

        JsonNode item = rest.getForObject("/api/reconciliations/" + id + "/breaks", JsonNode.class).at("/items/0");

        assertThat(item.get("type").asText()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(item.get("txnId").asText()).isEqualTo(txnId);
        assertThat(item.get("ledgerAmount").isTextual()).isTrue();
        assertThat(item.get("ledgerAmount").asText()).isEqualTo("0.100000000000000001");
        assertThat(item.get("externalAmount").asText()).isEqualTo("0.100000000000000002");
    }

    @Test
    void duplicateBreakRecordsFeedAndOccurrences() {
        String txnId = uniquePrefix();
        String row = txnId + ",ACC-1,BTC,1,SETTLED,2026-01-15T00:00:00Z\n";
        long id = reconcile(HEADER + row + row + row, HEADER + row).getBody().get("id").asLong();

        JsonNode item = rest.getForObject("/api/reconciliations/" + id + "/breaks", JsonNode.class).at("/items/0");

        assertThat(item.get("type").asText()).isEqualTo("DUPLICATE");
        assertThat(item.get("feedType").asText()).isEqualTo("LEDGER");
        assertThat(item.get("occurrences").asInt()).isEqualTo(3);
    }

    @Test
    void rejectedRowIsReportedAsMissingBecauseItNeverEnteredTheFeed() {
        String txnId = uniquePrefix();
        String ledger = HEADER + txnId + ",ACC-1,BTC,1E+3,SETTLED,2026-01-15T00:00:00Z\n";
        String external = HEADER + txnId + ",ACC-1,BTC,1000,SETTLED,2026-01-15T00:00:00Z\n";

        JsonNode body = reconcile(ledger, external).getBody();

        assertThat(body.at("/breakCounts/MISSING_INTERNAL").asLong()).isEqualTo(1);
    }

    @Test
    void sameFeedsCanBeReconciledAgainWithTheSameResult() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 20, 2);
        long ledger = uploadFeed("ledger", data.ledgerCsv());
        long external = uploadFeed("external", data.externalCsv());

        JsonNode first = start(ledger, external).getBody();
        JsonNode second = start(ledger, external).getBody();

        assertThat(second.get("id").asLong()).isNotEqualTo(first.get("id").asLong());
        assertThat(fetchAllBreaks(second.get("id").asLong()))
                .containsExactlyInAnyOrderElementsOf(fetchAllBreaks(first.get("id").asLong()));
    }

    @Test
    void failureIsRecordedAndStoresNoBreaks() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 5, 1);
        long ledger = uploadFeed("ledger", data.ledgerCsv());
        long external = uploadFeed("external", data.externalCsv());
        long maxBefore = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM reconciliation", Long.class);
        doThrow(new IllegalStateException("secret internal detail")).when(engine).match(any(), any());

        ResponseEntity<JsonNode> response = start(ledger, external);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().toString()).doesNotContain("secret internal detail");

        long id = jdbc.queryForObject("SELECT MAX(id) FROM reconciliation", Long.class);
        assertThat(id).isGreaterThan(maxBefore);
        JsonNode summary = rest.getForObject("/api/reconciliations/" + id, JsonNode.class);
        assertThat(summary.get("status").asText()).isEqualTo("FAILED");
        assertThat(summary.get("failureReason").asText()).doesNotContain("secret internal detail").isNotBlank();
        assertThat(summary.get("finishedAt").asText()).isNotBlank();
        assertThat(summary.get("totalBreaks").asLong()).isZero();
    }

    @Test
    void feedOfTheWrongTypeReturns400() {
        SampleData data = SampleDataGenerator.generate(42L, uniquePrefix(), 2, 0);
        long ledger = uploadFeed("ledger", data.ledgerCsv());
        long external = uploadFeed("external", data.externalCsv());

        ResponseEntity<JsonNode> response = start(external, ledger);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorFormat(response.getBody(), 400, "/api/reconciliations");
        assertThat(response.getBody().get("message").asText()).contains("is not a LEDGER feed");
    }

    @Test
    void unknownFeedReturns404AndCreatesNoRun() {
        long countBefore = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation", Long.class);

        ResponseEntity<JsonNode> response = start(999_999_999L, 999_999_998L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertErrorFormat(response.getBody(), 404, "/api/reconciliations");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation", Long.class)).isEqualTo(countBefore);
    }

    @Test
    void missingFieldsReturn400NamingTheField() {
        ResponseEntity<JsonNode> response = postJson("{\"ledgerFeedId\": 1}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorFormat(response.getBody(), 400, "/api/reconciliations");
        assertThat(response.getBody().get("message").asText()).contains("externalFeedId");
    }

    @Test
    void malformedJsonReturns400WithoutInternalDetails() {
        ResponseEntity<JsonNode> response = postJson("{not json");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message").asText()).isEqualTo("Malformed JSON request");
    }

    @Test
    void unknownReconciliationReturns404InTheSameFormat() {
        for (String path : List.of("/api/reconciliations/999999999", "/api/reconciliations/999999999/breaks")) {
            ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertErrorFormat(response.getBody(), 404, path);
        }
    }

    @Test
    void invalidBreakFilterAndPagingReturn400InTheSameFormat() {
        long id = reconcile(SampleDataGenerator.generate(42L, uniquePrefix(), 2, 0)).getBody().get("id").asLong();
        String path = "/api/reconciliations/" + id + "/breaks";

        for (String query : List.of("type=NOPE", "page=-1", "size=0", "size=201")) {
            ResponseEntity<JsonNode> response = rest.getForEntity(path + "?" + query, JsonNode.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertErrorFormat(response.getBody(), 400, path);
            assertThat(response.getBody().get("message").asText()).doesNotContain("java.");
        }
    }

    private List<ExpectedBreak> fetchAllBreaks(long id) {
        List<ExpectedBreak> found = new ArrayList<>();
        int page = 0;
        long totalPages;
        do {
            JsonNode body = rest.getForObject(
                    "/api/reconciliations/" + id + "/breaks?size=200&page=" + page, JsonNode.class);
            totalPages = body.get("totalPages").asLong();
            for (JsonNode item : body.get("items")) {
                JsonNode feedType = item.get("feedType");
                found.add(new ExpectedBreak(
                        BreakType.valueOf(item.get("type").asText()),
                        item.get("txnId").asText(),
                        feedType.isNull() ? null : FeedType.valueOf(feedType.asText())));
            }
            page++;
        } while (page < totalPages);
        return found;
    }

    private ResponseEntity<JsonNode> reconcile(SampleData data) {
        long ledger = uploadFeed("ledger", data.ledgerCsv());
        long external = uploadFeed("external", data.externalCsv());
        // A rejected row would look like a missing transaction, so generated files must be fully valid.
        assertThat(rest.getForObject("/api/feeds/" + ledger, JsonNode.class).get("rejectedRows").asInt()).isZero();
        assertThat(rest.getForObject("/api/feeds/" + external, JsonNode.class).get("rejectedRows").asInt()).isZero();
        return start(ledger, external);
    }

    /** Uploads the same rows to both sides, which must match perfectly. */
    private ResponseEntity<JsonNode> reconcile(String csv) {
        return reconcile(csv, csv);
    }

    private ResponseEntity<JsonNode> reconcile(String ledgerCsv, String externalCsv) {
        return start(uploadFeed("ledger", ledgerCsv), uploadFeed("external", externalCsv));
    }

    private ResponseEntity<JsonNode> start(long ledgerFeedId, long externalFeedId) {
        return postJson("{\"ledgerFeedId\": %d, \"externalFeedId\": %d}".formatted(ledgerFeedId, externalFeedId));
    }

    private ResponseEntity<JsonNode> postJson(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/reconciliations", new HttpEntity<>(json, headers), JsonNode.class);
    }

    private long uploadFeed(String type, String csv) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return type + ".csv";
            }
        });
        ResponseEntity<JsonNode> response =
                rest.postForEntity("/api/feeds/" + type, new HttpEntity<>(form, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    private void assertErrorFormat(JsonNode body, int status, String path) {
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("path").asText()).isEqualTo(path);
        assertThat(body.get("message").asText()).isNotBlank();
    }

    private String uniquePrefix() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }
}
