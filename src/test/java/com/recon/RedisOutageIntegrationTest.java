package com.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Stops a real Redis while the app is running. A mock throws instantly, but a real outage makes the
 * client wait for a timeout, and only this test proves requests still answer quickly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext
class RedisOutageIntegrationTest {

    private static final Duration FAST_ENOUGH = Duration.ofSeconds(8);
    private static final String HEADER = "txn_id,account_id,asset,amount,status,timestamp\n";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    // Its own container, because stopping the shared one would break every other test class.
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void appKeepsAnsweringQuicklyWhenRedisStops() {
        String row = UUID.randomUUID() + ",ACC-1,BTC,1,SETTLED,2026-01-15T00:00:00Z\n";
        String csv = HEADER + row;
        long ledger = upload("ledger", csv).getBody().get("id").asLong();
        long external = upload("external", csv).getBody().get("id").asLong();
        long reconciliation = start(ledger, external).getBody().get("id").asLong();

        REDIS.stop();

        // The lock has no backup, so a run must refuse to start.
        ResponseEntity<JsonNode> run = timed(() -> start(ledger, external));
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // Reads fall back to MySQL.
        ResponseEntity<JsonNode> summary = timed(
                () -> rest.getForEntity("/api/reconciliations/" + reconciliation, JsonNode.class));
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody().get("status").asText()).isEqualTo("COMPLETED");

        // Repeat detection falls back to the database too.
        assertThat(timed(() -> upload("ledger", csv)).getStatusCode()).isEqualTo(HttpStatus.OK);
        String newFile = HEADER + UUID.randomUUID() + ",ACC-1,BTC,1,SETTLED,2026-01-15T00:00:00Z\n";
        assertThat(timed(() -> upload("ledger", newFile)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<JsonNode> timed(java.util.function.Supplier<ResponseEntity<JsonNode>> request) {
        long start = System.nanoTime();
        ResponseEntity<JsonNode> response = request.get();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(FAST_ENOUGH);
        return response;
    }

    private ResponseEntity<JsonNode> start(long ledger, long external) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"ledgerFeedId\": %d, \"externalFeedId\": %d}".formatted(ledger, external);
        return rest.postForEntity("/api/reconciliations", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> upload(String type, String csv) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "feed.csv";
            }
        });
        return rest.postForEntity("/api/feeds/" + type, new HttpEntity<>(form, headers), JsonNode.class);
    }
}
