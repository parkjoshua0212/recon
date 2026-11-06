# Recon

A transaction reconciliation service. It compares an internal ledger with an external statement and reports every mismatch.

Status: Milestone 1 (project skeleton). The full README comes in Milestone 6.

## Requirements

- JDK 21
- Docker Desktop

## Run locally

```bash
docker compose up -d --wait
./gradlew bootRun
curl http://localhost:8080/actuator/health
```

## Test

Docker must be running. Testcontainers starts its own MySQL and Redis.

```bash
./gradlew test
```

## Known upgrade item

This project uses Spring Boot 3.5.16, the last open-source 3.5 release. Free support ended on June 30, 2026, so a production version should move to a supported 4.x release.
