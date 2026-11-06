package com.recon.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.recon.TestcontainersConfiguration;
import com.recon.feed.domain.FeedType;
import com.recon.feed.repository.FeedRepository;
import com.recon.feed.service.FeedIdempotencyCache;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Runs through real HTTP against real MySQL and Redis, with one spy for forcing the race. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class FeedUploadIntegrationTest {

    private static final String HEADER = "txn_id,account_id,asset,amount,status,timestamp\n";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private FeedRepository feedRepository;

    @Test
    void newFileReturns201AndStoresGoodAndBadRows() {
        String txnId = uniqueId();
        String csv = HEADER
                + txnId + ",ACC-1,BTC,0.123456789012345678,SETTLED,2026-01-15T09:30:00+09:00\n"
                + txnId + "-bad,ACC-1,BTC,1E+3,SETTLED,2026-01-15T00:00:00Z\n"
                + txnId + "-2,ACC-1,USDT,-250.50,PENDING,2026-01-15T00:00:00Z\n";

        ResponseEntity<JsonNode> response = upload("ledger", csv);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        long feedId = body.get("id").asLong();
        assertThat(response.getHeaders().getLocation()).hasToString("/api/feeds/" + feedId);
        assertThat(body.get("feedType").asText()).isEqualTo("LEDGER");
        assertThat(body.get("fileHash").asText()).isEqualTo(sha256(csv));
        assertThat(body.get("originalFilename").asText()).isEqualTo("feed.csv");
        assertThat(body.get("totalRows").asInt()).isEqualTo(3);
        assertThat(body.get("acceptedRows").asInt()).isEqualTo(2);
        assertThat(body.get("rejectedRows").asInt()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM feed_transaction WHERE feed_id = ? AND line_number = 2", feedId);
        assertThat(row.get("txn_id")).isEqualTo(txnId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("0.123456789012345678");
        assertThat(asLocalDateTime(row.get("txn_time"))).isEqualTo(LocalDateTime.of(2026, 1, 15, 0, 30));

        assertThat(redis.opsForValue().get(FeedIdempotencyCache.key(FeedType.LEDGER, sha256(csv))))
                .isEqualTo(Long.toString(feedId));
    }

    @Test
    void storesMoreRowsThanOneBatch() {
        StringBuilder csv = new StringBuilder(HEADER);
        String prefix = uniqueId();
        for (int i = 0; i < 2_500; i++) {
            csv.append(prefix).append('-').append(i).append(",ACC-1,BTC,1,SETTLED,2026-01-15T00:00:00Z\n");
        }
        for (int i = 0; i < 1_100; i++) {
            csv.append("bad-row\n");
        }

        JsonNode body = upload("ledger", csv.toString()).getBody();

        assertThat(body.get("acceptedRows").asInt()).isEqualTo(2_500);
        assertThat(body.get("rejectedRows").asInt()).isEqualTo(1_100);
        assertThat(countRows("feed_transaction", body.get("id").asLong())).isEqualTo(2_500);
        assertThat(countRows("feed_row_error", body.get("id").asLong())).isEqualTo(1_100);
    }

    @Test
    void feedWithOnlyBadRowsIsStillStoredAndReported() {
        ResponseEntity<JsonNode> response = upload("external", HEADER + "not-a-row-" + uniqueId() + "\n");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("acceptedRows").asInt()).isZero();
        assertThat(response.getBody().get("rejectedRows").asInt()).isEqualTo(1);
    }

    @Test
    void repeatedFileReturns200WithTheOriginalFeedAndAddsNothing() {
        String csv = validCsv();
        ResponseEntity<JsonNode> first = upload("ledger", csv);
        long feedId = first.getBody().get("id").asLong();

        ResponseEntity<JsonNode> second = upload("ledger", csv);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(countFeeds(FeedType.LEDGER, sha256(csv))).isEqualTo(1);
        assertThat(countRows("feed_transaction", feedId)).isEqualTo(1);
    }

    @Test
    void redisHitAnswersWithoutTheDatabaseLookup() {
        String csv = validCsv();
        upload("ledger", csv);
        clearInvocations(feedRepository);

        upload("ledger", csv);

        verify(feedRepository, never()).findByFeedTypeAndFileHash(any(), anyString());
    }

    @Test
    void sameBytesAsTheOtherFeedTypeAreASeparateFeed() {
        String csv = validCsv();

        ResponseEntity<JsonNode> ledger = upload("ledger", csv);
        ResponseEntity<JsonNode> external = upload("external", csv);

        assertThat(external.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(external.getBody().get("id").asLong()).isNotEqualTo(ledger.getBody().get("id").asLong());
    }

    @Test
    void oneChangedByteIsANewFile() {
        String csv = validCsv();
        upload("ledger", csv);

        assertThat(upload("ledger", csv + "\n").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void missingRedisKeyFallsBackToTheDatabaseAndIsRestored() {
        String csv = validCsv();
        long feedId = upload("ledger", csv).getBody().get("id").asLong();
        String key = FeedIdempotencyCache.key(FeedType.LEDGER, sha256(csv));
        redis.delete(key);

        ResponseEntity<JsonNode> repeat = upload("ledger", csv);

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeat.getBody().get("id").asLong()).isEqualTo(feedId);
        assertThat(countFeeds(FeedType.LEDGER, sha256(csv))).isEqualTo(1);
        assertThat(redis.opsForValue().get(key)).isEqualTo(Long.toString(feedId));
    }

    @Test
    void redisKeyPointingAtAnUnrelatedFeedIsIgnored() {
        long otherFeedId = upload("ledger", validCsv()).getBody().get("id").asLong();
        String csv = validCsv();
        redis.opsForValue().set(FeedIdempotencyCache.key(FeedType.LEDGER, sha256(csv)), Long.toString(otherFeedId));

        ResponseEntity<JsonNode> response = upload("ledger", csv);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("id").asLong()).isNotEqualTo(otherFeedId);
    }

    /**
     * Forces the race every time: the first lookup pretends the feed does not exist, exactly what a
     * losing upload sees, so the INSERT really hits the UNIQUE constraint.
     */
    @Test
    void uniqueConstraintViolationReturnsTheExistingFeed() {
        String csv = validCsv();
        String hash = sha256(csv);
        long feedId = upload("ledger", csv).getBody().get("id").asLong();
        redis.delete(FeedIdempotencyCache.key(FeedType.LEDGER, hash));
        clearInvocations(feedRepository);
        doReturn(Optional.empty())
                .doAnswer(invocation -> feedRepository.findById(feedId))
                .when(feedRepository)
                .findByFeedTypeAndFileHash(FeedType.LEDGER, hash);

        ResponseEntity<JsonNode> response = upload("ledger", csv);

        // The second lookup only exists in the violation handler, so this proves that path ran.
        verify(feedRepository, times(2)).findByFeedTypeAndFileHash(FeedType.LEDGER, hash);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("id").asLong()).isEqualTo(feedId);
        assertThat(response.getBody().get("acceptedRows").asInt()).isEqualTo(1);
        assertThat(countFeeds(FeedType.LEDGER, hash)).isEqualTo(1);
        assertThat(countRows("feed_transaction", feedId)).isEqualTo(1);
        assertThat(redis.opsForValue().get(FeedIdempotencyCache.key(FeedType.LEDGER, hash)))
                .isEqualTo(Long.toString(feedId));
    }

    @Test
    void concurrentIdenticalUploadsCreateOneFeed() throws Exception {
        int uploads = 8;
        StringBuilder csv = new StringBuilder(HEADER);
        String prefix = uniqueId();
        for (int i = 0; i < 2_000; i++) {
            csv.append(prefix).append('-').append(i).append(",ACC-1,BTC,1,SETTLED,2026-01-15T00:00:00Z\n");
        }
        CountDownLatch start = new CountDownLatch(1);

        List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(uploads)) {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (int i = 0; i < uploads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return upload("ledger", csv.toString());
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                responses.add(future.get());
            }
        }

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).extracting(r -> r.getBody().get("id").asLong()).containsOnly(
                responses.get(0).getBody().get("id").asLong());
        // Every caller sees finished counts, never a half-written feed.
        assertThat(responses).extracting(r -> r.getBody().get("acceptedRows").asInt()).containsOnly(2_000);

        long feedId = responses.get(0).getBody().get("id").asLong();
        assertThat(countFeeds(FeedType.LEDGER, sha256(csv.toString()))).isEqualTo(1);
        assertThat(countRows("feed_transaction", feedId)).isEqualTo(2_000);
    }

    @Test
    void badHeaderReturns400AndStoresNothing() {
        String csv = "id,amount\n" + uniqueId() + ",1\n";

        assertBadFileStoresNothing(csv.getBytes(StandardCharsets.UTF_8), "Header must be exactly");
    }

    @Test
    void headerWithoutRowsReturns400AndStoresNothing() {
        String csv = HEADER.strip() + " ".repeat(new java.util.Random().nextInt(1, 1000));

        assertBadFileStoresNothing(csv.getBytes(StandardCharsets.UTF_8), "no data rows");
    }

    @Test
    void invalidBytesAfterGoodRowsRollBackEverything() {
        byte[] good = validCsv().getBytes(StandardCharsets.UTF_8);
        byte[] file = new byte[good.length + 1];
        System.arraycopy(good, 0, file, 0, good.length);
        file[good.length] = (byte) 0xFF;
        long rowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM feed_transaction", Long.class);

        assertBadFileStoresNothing(file, "UTF-8");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_transaction", Long.class)).isEqualTo(rowsBefore);
    }

    @Test
    void emptyFileReturns400() {
        ResponseEntity<JsonNode> response = uploadBytes("ledger", new byte[0]);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorFormat(response.getBody(), 400, "/api/feeds/ledger");
    }

    @Test
    void missingFileFieldReturns400InTheSameFormat() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("wrong", "value");

        ResponseEntity<JsonNode> response =
                rest.postForEntity("/api/feeds/ledger", new HttpEntity<>(form, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorFormat(response.getBody(), 400, "/api/feeds/ledger");
    }

    @Test
    void fileOverTheLimitReturns413InTheSameFormat() {
        byte[] tooBig = new byte[50 * 1024 * 1024 + 1];

        ResponseEntity<JsonNode> response = uploadBytes("ledger", tooBig);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertErrorFormat(response.getBody(), 413, "/api/feeds/ledger");
    }

    @Test
    void getFeedReturnsTheSummary() {
        JsonNode created = upload("external", validCsv()).getBody();

        ResponseEntity<JsonNode> response =
                rest.getForEntity("/api/feeds/" + created.get("id").asLong(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(created);
    }

    @Test
    void unknownFeedReturns404InTheSameFormat() {
        for (String path : List.of("/api/feeds/999999999", "/api/feeds/999999999/errors")) {
            ResponseEntity<JsonNode> response = rest.getForEntity(path, JsonNode.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertErrorFormat(response.getBody(), 404, path);
        }
    }

    @Test
    void nonNumericFeedIdReturns400InTheSameFormat() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/feeds/abc", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorFormat(response.getBody(), 400, "/api/feeds/abc");
    }

    @Test
    void errorsArePagedInLineOrder() {
        String id = uniqueId();
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 5; i++) {
            csv.append(id).append("-bad-").append(i).append('\n');
        }
        long feedId = upload("ledger", csv.toString()).getBody().get("id").asLong();

        JsonNode first = rest.getForObject("/api/feeds/" + feedId + "/errors?page=0&size=2", JsonNode.class);
        JsonNode last = rest.getForObject("/api/feeds/" + feedId + "/errors?page=2&size=2", JsonNode.class);

        assertThat(first.get("totalItems").asInt()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.at("/items/0/lineNumber").asInt()).isEqualTo(2);
        assertThat(first.at("/items/0/rawLine").asText()).isEqualTo(id + "-bad-0");
        assertThat(first.at("/items/0/reason").asText()).contains("expected 6 columns");
        assertThat(last.get("items")).hasSize(1);
        assertThat(last.at("/items/0/lineNumber").asInt()).isEqualTo(6);
    }

    @Test
    void invalidPagingReturns400InTheSameFormat() {
        long feedId = upload("ledger", validCsv()).getBody().get("id").asLong();

        for (String query : List.of("page=-1", "size=0", "size=201")) {
            ResponseEntity<JsonNode> response =
                    rest.getForEntity("/api/feeds/" + feedId + "/errors?" + query, JsonNode.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertErrorFormat(response.getBody(), 400, "/api/feeds/" + feedId + "/errors");
        }
    }

    private void assertBadFileStoresNothing(byte[] file, String expectedMessage) {
        String hash = sha256(file);

        ResponseEntity<JsonNode> response = uploadBytes("ledger", file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorFormat(response.getBody(), 400, "/api/feeds/ledger");
        assertThat(response.getBody().get("message").asText()).contains(expectedMessage);
        assertThat(countFeeds(FeedType.LEDGER, hash)).isZero();
        assertThat(redis.hasKey(FeedIdempotencyCache.key(FeedType.LEDGER, hash))).isFalse();
    }

    private void assertErrorFormat(JsonNode body, int status, String path) {
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("path").asText()).isEqualTo(path);
        assertThat(body.get("message").asText()).isNotBlank();
    }

    private ResponseEntity<JsonNode> upload(String type, String csv) {
        return uploadBytes(type, csv.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<JsonNode> uploadBytes(String type, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "feed.csv";
            }
        });
        return rest.postForEntity("/api/feeds/" + type, new HttpEntity<>(form, headers), JsonNode.class);
    }

    /** Random content gives each test its own hash, since all tests share one database. */
    private String validCsv() {
        return HEADER + uniqueId() + ",ACC-1,BTC,1.5,SETTLED,2026-01-15T00:00:00Z\n";
    }

    private String uniqueId() {
        return UUID.randomUUID().toString();
    }

    private long countRows(String table, long feedId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE feed_id = ?", Long.class, feedId);
    }

    private long countFeeds(FeedType type, String hash) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM feed WHERE feed_type = ? AND file_hash = ?", Long.class, type.name(), hash);
    }

    private LocalDateTime asLocalDateTime(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    private String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
