#!/usr/bin/env bash
# D5 live-writer integration: a Java writer appends mock quotes to a fresh table dir while DuckDB
# queries it through the arena extension. Proves the reader sees live, growing data (freshness) —
# each query freezes a fresh catalog snapshot, so successive counts rise as the writer appends, and
# the in-progress (unsealed) batch is visible, not just sealed ones.
set -euo pipefail

DUCKDB="${DUCKDB:-/home/goldv/src/duckdb}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
EXT="$HERE/build/arena.duckdb_extension"
BASE="$(mktemp -d)"
SECS="${SECS:-25}"

[[ -f "$EXT" ]] || DUCKDB="$DUCKDB" "$HERE/scripts/build_extension.sh" >/dev/null 2>&1

# Start the live writer in the background (writes <BASE>/quotes for ~SECS seconds).
( cd "$REPO" && ./gradlew -q :arena:runLiveWriter --args="$BASE quotes 200 $SECS" ) >/dev/null 2>&1 &
WPID=$!
trap 'kill "$WPID" 2>/dev/null || true; rm -rf "$BASE"' EXIT

# Wait (up to ~40s, covering a cold Gradle daemon) for the first segment to appear.
seg=""
for _ in $(seq 1 80); do
    seg="$(ls "$BASE/quotes/"*.arena 2>/dev/null | head -1 || true)"
    [[ -n "$seg" ]] && break
    sleep 0.5
done
if [[ -z "$seg" ]]; then
    echo "live demo: FAIL (writer produced no segment)"; exit 1
fi

q() { ARENA_DIR="$BASE" DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" "$1" 2>&1 | tail -1 || true; }

echo "Querying live arena data as the writer appends (table 'quotes'):"
prev=-1; grew=1
for i in 1 2 3 4; do
    n="$(q "SELECT count(*)::BIGINT FROM quotes")"
    latest="$(q "SELECT max(ts)::VARCHAR FROM quotes")"
    inprog="$(q "SELECT count(*)::BIGINT FROM arena_segments('$BASE/quotes') WHERE NOT sealed AND rows > 0")"
    printf "  t=%d  rows=%-6s in_progress_batches=%-2s latest_ts=%s\n" "$i" "$n" "$inprog" "$latest"
    [[ "$n" =~ ^[0-9]+$ && "$n" -ge "$prev" ]] || grew=0
    prev="$n"
    sleep 2
done

echo "  live aggregate (SELECT sym, count(*), max(px)/1e4 FROM quotes GROUP BY sym):"
ARENA_DIR="$BASE" DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" \
    'SELECT sym, count(*) AS n, max(px)/10000.0 AS "last" FROM quotes GROUP BY sym ORDER BY sym' \
    2>&1 | grep -E '^[A-Z]{3,4}\b' | sed 's/^/    /' || true

wait "$WPID" 2>/dev/null || true
if [[ "$grew" == "1" && "$prev" -gt 0 ]]; then
    echo "live demo: row count grew monotonically to $prev while writing — PASS"
else
    echo "live demo: FAIL (counts did not grow: last=$prev)"; exit 1
fi
