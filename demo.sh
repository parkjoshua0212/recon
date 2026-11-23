#!/usr/bin/env bash
# One-command demo: start MySQL and Redis, start the app if needed, upload two feeds,
# reconcile them and print what was found.
# Usage: ./demo.sh [ledger.csv external.csv]   (defaults to the files in samples/)
set -euo pipefail
cd "$(dirname "$0")"

LEDGER=${1:-samples/ledger.csv}
EXTERNAL=${2:-samples/external.csv}
BASE=http://localhost:8080
API=$BASE/api

for tool in docker curl jq; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool"; exit 1; }
done

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# Prints the status code and body; stops the demo on any non-2xx answer.
call() {
  local out code
  out=$(curl -sS -w '\n%{http_code}' "$@")
  code=${out##*$'\n'}
  out=${out%$'\n'*}
  echo "HTTP $code" >&2
  if [[ $code != 2* ]]; then echo "$out" | jq . >&2 || echo "$out" >&2; exit 1; fi
  echo "$out"
}

step "Starting MySQL and Redis"
docker compose up -d --wait

if curl -sf $BASE/actuator/health >/dev/null; then
  echo "App is already running."
else
  step "Starting the app (log: build/demo-app.log)"
  mkdir -p build
  ./gradlew bootRun >build/demo-app.log 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 120); do
    curl -sf $BASE/actuator/health >/dev/null && break
    kill -0 $APP_PID 2>/dev/null || { echo "App failed to start; see build/demo-app.log"; exit 1; }
    sleep 1
  done
  curl -sf $BASE/actuator/health >/dev/null || { echo "App not healthy after 120s; see build/demo-app.log"; exit 1; }
  echo "App is up (pid $APP_PID)."
fi

# Uploads one feed, prints its counts and rejected rows, and returns the feed id on stdout.
upload() {
  local kind=$1 file=$2 feed id
  feed=$(call -F "file=@$file" "$API/feeds/$kind")
  id=$(jq -r .id <<<"$feed")
  jq -r '"Feed \(.id): \(.totalRows) rows, \(.acceptedRows) accepted, \(.rejectedRows) rejected"' <<<"$feed" >&2
  if [[ $(jq .rejectedRows <<<"$feed") != 0 ]]; then
    echo "Rejected rows (reported, never dropped):" >&2
    call "$API/feeds/$id/errors" | jq -r '.items[] | "  line \(.lineNumber): \(.reason)\n    \(.rawLine)"' >&2
  fi
  echo "$id"
}

step "1. Upload internal ledger: $LEDGER"
LEDGER_ID=$(upload ledger "$LEDGER")

step "2. Upload external statement: $EXTERNAL"
EXTERNAL_ID=$(upload external "$EXTERNAL")

step "3. Reconcile feed $LEDGER_ID against feed $EXTERNAL_ID"
SUMMARY=$(call -H 'Content-Type: application/json' \
  -d "{\"ledgerFeedId\":$LEDGER_ID,\"externalFeedId\":$EXTERNAL_ID}" $API/reconciliations)
RUN_ID=$(jq -r .id <<<"$SUMMARY")
jq '{id, status, durationMillis, totalBreaks, breakCounts}' <<<"$SUMMARY"

step "4. Breaks found (first 20)"
call "$API/reconciliations/$RUN_ID/breaks?size=20" \
  | jq -r '.items[] | [.type, .txnId,
      (if .ledgerAmount != .externalAmount then "amount \(.ledgerAmount // "-") vs \(.externalAmount // "-")" else empty end),
      (if .ledgerStatus != .externalStatus then "status \(.ledgerStatus // "-") vs \(.externalStatus // "-")" else empty end)]
    | join("  ")'

step "Done"
echo "Swagger UI: $BASE/swagger-ui.html"
echo "Breaks:     $API/reconciliations/$RUN_ID/breaks"
[[ -n ${APP_PID:-} ]] && echo "The app is still running for Swagger. Stop it with: kill $APP_PID"
true
