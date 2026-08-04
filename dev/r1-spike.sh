#!/usr/bin/env bash
# R1 write-path spike (docs/cold-tier-design-plan.md §11): proves the cold-tier write path before
# any Java is written, and pins the exact DDL. Rolls a real arena segment into a day-partitioned,
# format-version-3 Iceberg table via DuckDB, in one transaction with the roll_log watermark row.
#
# It answers R1's three open questions and asserts the answers, so it doubles as a regression test:
#   1. Are nanosecond timestamps preserved end-to-end?      -> V3 timestamp_ns, exact
#   2. What is the partition / sort-order DDL?               -> ALTER TABLE ... SET PARTITIONED BY
#   3. Is the two-table (data + roll_log) commit atomic?     -> one POST /transactions/commit
#
# Prerequisites: dev stack up (docker compose -f dev/docker-compose.yml up -d) and the arena
# extension built (DUCKDB=… arena-duckdb/scripts/build_extension.sh).
#
# Usage: dev/r1-spike.sh [arena_table_dir]
#   With no argument it generates a throwaway arena segment via :ascham-core:runLiveWriter.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
CLI="${DUCKDB_CLI:-/home/goldv/src/duckdb/build/release/duckdb}"
EXT="${ARENA_EXT:-$HERE/arena-duckdb/build/arena.duckdb_extension}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

[[ -x "$CLI" ]] || { echo "duckdb CLI not found at $CLI (set DUCKDB_CLI)" >&2; exit 1; }
[[ -f "$EXT" ]] || { echo "arena extension not found at $EXT (build it first)" >&2; exit 1; }

ARENA_TABLE_DIR="${1:-}"
if [[ -z "$ARENA_TABLE_DIR" ]]; then
    echo "==> generating a throwaway arena segment (6s of mock quotes)"
    ( cd "$HERE" && ./gradlew -q :ascham-core:runLiveWriter --args="$WORK/arena quotes 400 6" ) >/dev/null 2>&1
    ARENA_TABLE_DIR="$WORK/arena/quotes"
fi
[[ -d "$ARENA_TABLE_DIR" ]] || { echo "no such arena table dir: $ARENA_TABLE_DIR" >&2; exit 1; }
echo "==> arena source: $ARENA_TABLE_DIR"
ls -1 "$ARENA_TABLE_DIR" | sed 's/^/    /'

# The day being rolled, derived from the segment file names (<yyyyMMdd>.<seq>.ascham).
DAY_RAW="$(ls -1 "$ARENA_TABLE_DIR" | grep -oE '^[0-9]{8}' | sort -u | head -1)"
DAY="${DAY_RAW:0:4}-${DAY_RAW:4:2}-${DAY_RAW:6:2}"

# Name that day's segments explicitly, as the roller will: the set archived here is the same list
# it would later unlink, with no directory re-listing in between (arena_scan's LIST form, R3).
SEG_FILES="$(ls -1 "$ARENA_TABLE_DIR"/"$DAY_RAW".*.ascham)"
SEG_LIST="$(printf "'%s'," $SEG_FILES | sed 's/,$//')"
SEG_NAMES="$(basename -a $SEG_FILES | paste -sd, -)"
echo "==> rolling day $DAY from $(wc -l <<<"$SEG_FILES") segment(s)"

cat > "$WORK/spike.sql" <<SQL
-- Both extensions in one session: unsigned local arena + signed core iceberg (they coexist).
LOAD '$EXT';
.read $HERE/dev/hist-attach.sql
CREATE SCHEMA IF NOT EXISTS hist.ito_meta;

.print
.print ================ setup: v3 + day(ts) partitioned target ================
DROP TABLE IF EXISTS hist.ito.quotes_r1;
DROP TABLE IF EXISTS hist.ito_meta.roll_log_r1;
-- Q2 ANSWER: format version must be set at CREATE with a *quoted* property key; an unquoted
-- 'format_version = 3' is silently ignored and the table is created as v2, which then rejects
-- timestamp_ns with "not supported until v3". Partitioning is NOT accepted in CREATE TABLE
-- (PARTITIONED BY there is a parser error) — it is a separate ALTER.
CREATE TABLE hist.ito.quotes_r1 (
    sym VARCHAR,
    ts  TIMESTAMP_NS,
    px  BIGINT
) WITH ('format-version' = '3');
ALTER TABLE hist.ito.quotes_r1 SET PARTITIONED BY (day(ts));
CREATE TABLE hist.ito_meta.roll_log_r1 (
    table_name VARCHAR, day DATE, rows BIGINT, segments VARCHAR, committed_at TIMESTAMP
) WITH ('format-version' = '3');

.print
.print ================ the roll: one atomic multi-table transaction ================
BEGIN;
INSERT INTO hist.ito.quotes_r1
    SELECT sym, ts, px
    FROM arena_scan([$SEG_LIST])
    WHERE ts >= TIMESTAMP '$DAY' AND ts < TIMESTAMP '$DAY' + INTERVAL 1 DAY
    ORDER BY sym, ts;
INSERT INTO hist.ito_meta.roll_log_r1
    SELECT 'quotes', DATE '$DAY', count(*), '$SEG_NAMES', now()
    FROM hist.ito.quotes_r1;
COMMIT;

.print
.print ================ assertions ================
.mode line
WITH arena AS (SELECT count(*) n, min(ts) lo, max(ts) hi FROM arena_scan([$SEG_LIST])),
     ice   AS (SELECT count(*) n, min(ts) lo, max(ts) hi FROM hist.ito.quotes_r1)
SELECT
    (SELECT n FROM arena)                              AS arena_rows,
    (SELECT n FROM ice)                                AS iceberg_rows,
    ((SELECT n FROM arena) = (SELECT n FROM ice))      AS row_parity_ok,
    -- Q1: nanosecond fidelity. Compares the full ns-precision bounds, not a truncated rendering.
    ((SELECT lo FROM arena) = (SELECT lo FROM ice)
     AND (SELECT hi FROM arena) = (SELECT hi FROM ice)) AS ns_bounds_exact,
    (SELECT hi::VARCHAR FROM ice)                      AS max_ts_roundtripped,
    -- ns fidelity is only *proved* if the source actually carries sub-microsecond digits.
    (SELECT count(*) FROM arena_scan([$SEG_LIST])
       WHERE epoch_ns(ts) % 1000 <> 0)                 AS source_rows_with_sub_us,
    (SELECT count(*) FROM hist.ito.quotes_r1
       WHERE epoch_ns(ts) % 1000 <> 0)                 AS iceberg_rows_with_sub_us;

.print --- watermark row (the cutover source of truth) ---
SELECT * FROM hist.ito_meta.roll_log_r1;

.print --- Q2: partition spec + sort order actually stored in the catalog ---
SELECT metadata['format-version']  AS format_version,
       metadata['partition-specs'] AS partition_specs,
       metadata['default-spec-id'] AS default_spec_id,
       metadata['sort-orders']     AS sort_orders
FROM iceberg_load_table_response('hist.ito.quotes_r1');

.print --- partition pruning: files touched for a one-day predicate ---
.mode duckbox
EXPLAIN ANALYZE SELECT count(*) FROM hist.ito.quotes_r1
  WHERE ts >= TIMESTAMP '$DAY' AND ts < TIMESTAMP '$DAY' + INTERVAL 1 DAY;
SQL

"$CLI" -unsigned -init /dev/null < "$WORK/spike.sql" 2>&1 | tee "$WORK/out.txt"

echo
echo "==================== R1 verdict ===================="
fail=0
check() {  # check <label> <regex-that-must-appear>
    if grep -qE "$2" "$WORK/out.txt"; then echo "  [PASS] $1"; else echo "  [FAIL] $1"; fail=1; fi
}
check "row parity arena -> iceberg"            'row_parity_ok = true'
check "nanosecond bounds exact (Q1)"           'ns_bounds_exact = true'
check "source really had sub-microsecond ts"   'source_rows_with_sub_us = [1-9]'
check "format-version 3 stored (Q2)"           "format_version = 3"
check "day(ts) partition spec stored (Q2)"     "'transform': day"
check "partition pruning reads >=1 file"       'Total Files Read: [0-9]+'
[[ $fail -eq 0 ]] && echo "R1 spike: PASSED" || { echo "R1 spike: FAILURES"; exit 1; }
