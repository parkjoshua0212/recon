# Recon

A transaction reconciliation service. It compares an internal ledger with an external statement and reports every mismatch.

Status: Milestone 2 (CSV upload, validation, and Redis idempotency). The full README comes in Milestone 6.

## Requirements

- JDK 21
- Docker Desktop

## Run locally

```bash
docker compose up -d --wait
./gradlew bootRun
curl http://localhost:8080/actuator/health
```

## Upload a feed

CSV columns, in this order: `txn_id,account_id,asset,amount,status,timestamp`

```bash
curl -i -F file=@samples/ledger.csv http://localhost:8080/api/feeds/ledger
curl -i -F file=@samples/external.csv http://localhost:8080/api/feeds/external
curl http://localhost:8080/api/feeds/1
curl "http://localhost:8080/api/feeds/1/errors?page=0&size=50"
```

A new file returns 201. Uploading the same file again returns the original feed with 200. Bad rows are saved with a reason, and a bad file returns 400 and stores nothing.

## Test

Docker must be running. Testcontainers starts its own MySQL and Redis.

```bash
./gradlew test
```

## Known upgrade item

This project uses Spring Boot 3.5.16, the last open-source 3.5 release. Free support ended on June 30, 2026, so a production version should move to a supported 4.x release.
