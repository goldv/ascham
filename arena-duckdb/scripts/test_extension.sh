#!/usr/bin/env bash
# Builds the arena extension and checks arena_segments against the golden corpus through DuckDB SQL,
# via the libduckdb.so host. The C++ reader-core suite (make test) validates decoding; this validates
# the DuckDB extension surface end-to-end.
set -euo pipefail

DUCKDB="${DUCKDB:-/home/goldv/src/duckdb}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
CONFORMANCE="${CONFORMANCE_DIR:-$HERE/../conformance}"
EXT="$HERE/build/arena.duckdb_extension"
G="$CONFORMANCE/golden"

echo "building extension..."
DUCKDB="$DUCKDB" "$HERE/scripts/build_extension.sh" >/dev/null 2>&1

run() { DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" "$1" 2>&1; }

fail=0
check() {  # name  sql  expected-substring
    local out
    out="$(run "$2" || true)"  # a query that errors is a valid expectation (e.g. unknown file)
    if grep -qF -- "$3" <<<"$out"; then
        echo "  [PASS] $1"
    else
        echo "  [FAIL] $1 (expected to contain: $3)"
        sed 's/^/        /' <<<"$out"
        fail=1
    fi
}

check "all_types: batch 0 sealed with 4 rows" \
    "SELECT batch||':'||rows||':'||sealed AS r FROM arena_segments('$G/all_types.bin') ORDER BY batch" "0:4:true"
check "all_types: batch 1 in-progress with 2 rows" \
    "SELECT batch||':'||rows||':'||sealed AS r FROM arena_segments('$G/all_types.bin') ORDER BY batch" "1:2:false"
check "all_types: sealed-batch zone-map stats" \
    "SELECT ts_min||'/'||ts_max||'/'||stat_max AS r FROM arena_segments('$G/all_types.bin') WHERE batch=0" \
    "1700000000000000000/1700000000000000003/3000000"
check "aggregation: total rows across batches" \
    "SELECT sum(rows)::BIGINT AS r FROM arena_segments('$G/all_types.bin')" "6"
check "type_bounds: one sealed batch of 3 rows" \
    "SELECT rows AS r FROM arena_segments('$G/type_bounds.bin') WHERE sealed" "3"
check "varlen_empty: sealed batch of 3 rows" \
    "SELECT rows AS r FROM arena_segments('$G/varlen_empty.bin') WHERE sealed" "3"
check "unknown file: clean error" \
    "SELECT * FROM arena_segments('$G/does_not_exist.bin')" "arena_segments"

# --- arena_scan (the columnar table function) ---
check "arena_scan: all rows incl. the in-progress batch" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan('$G/all_types.bin')" "6"
check "arena_scan: typed aggregation with WHERE" \
    "SELECT sum(u16)::BIGINT AS r FROM arena_scan('$G/all_types.bin') WHERE flag" "42"
check "arena_scan: nanosecond timestamp precision" \
    "SELECT ts::VARCHAR AS r FROM arena_scan('$G/all_types.bin') WHERE i8 = 1" "2023-11-14 22:13:20.000000001"
check "arena_scan: decimal(38,9) scale preserved" \
    "SELECT dec::VARCHAR AS r FROM arena_scan('$G/all_types.bin') WHERE i8 = 1" "0.123456789"
check "arena_scan: date and time round-trip" \
    "SELECT d32::VARCHAR||' '||t64::VARCHAR AS r FROM arena_scan('$G/all_types.bin') WHERE i8 = 1" \
    "2022-01-09 00:00:00.000001"
check "arena_scan: varlen (utf8) value" \
    "SELECT sym AS r FROM arena_scan('$G/all_types.bin') WHERE i8 = 3" "s3"
check "arena_scan: unsigned bigint maximum" \
    "SELECT u64::VARCHAR AS r FROM arena_scan('$G/type_bounds.bin') WHERE u64 > 0" "18446744073709551615"
check "arena_scan: null validity" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan('$G/all_null.bin') WHERE i32 IS NULL" "3"
check "arena_scan: empty varlen strings" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan('$G/varlen_empty.bin') WHERE sym = ''" "3"

# --- D4: filter pushdown (row-exact), zone-map pruning, parallelism ---
check "pushdown: equality filter applied exactly" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan('$G/all_types.bin') WHERE i8 = 5" "1"
check "pushdown: compound filter (LIKE + range, two columns)" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan('$G/all_types.bin') WHERE sym LIKE 's_' AND i64 > 1000000" "4"

SEGDIR=$(mktemp -d)
for s in 0 1 2; do cp "$G/all_types.bin" "$SEGDIR/20260101.$s.arena"; done
check "parallel: 3 segments = 18 rows" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan('$SEGDIR')" "18"
check "zone-map: result stays exact under pruning" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan('$SEGDIR') WHERE i64 > 3500000" "6"

# --- R3: arena_scan(LIST(VARCHAR)) — name the segments explicitly, no directory listing.
# The cold-tier roller uses this so the set it archives is the same list it later unlinks
# (docs/cold-tier-design-plan.md §8.2).
check "list: scans exactly the named segments" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan(['$SEGDIR/20260101.0.arena','$SEGDIR/20260101.1.arena'])" "12"
check "list: single element" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan(['$SEGDIR/20260101.0.arena'])" "6"
check "list: equals the dir scan when it names every segment" \
    "SELECT (SELECT count(*) FROM arena_scan(['$SEGDIR/20260101.0.arena','$SEGDIR/20260101.1.arena','$SEGDIR/20260101.2.arena']))
          = (SELECT count(*) FROM arena_scan('$SEGDIR')) AS r" "true"
check "list: pushdown still applies" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan(['$SEGDIR/20260101.0.arena','$SEGDIR/20260101.1.arena']) WHERE i8 >= 4" "4"
check "list: zone-map pruning still applies" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan(['$SEGDIR/20260101.0.arena','$SEGDIR/20260101.1.arena']) WHERE i64 > 3500000" "4"
# Duplicates are rejected, not deduplicated: silently scanning a file twice would double-count its
# rows into the historical store.
check "list: duplicate entry is rejected" \
    "SELECT count(*) FROM arena_scan(['$SEGDIR/20260101.0.arena','$SEGDIR/20260101.0.arena'])" \
    "duplicate segment in path list"
check "list: empty list is rejected" \
    "SELECT count(*) FROM arena_scan([]::VARCHAR[])" "path list is empty"
check "list: NULL entry is rejected" \
    "SELECT count(*) FROM arena_scan(['$SEGDIR/20260101.0.arena',NULL])" "path list contains a NULL entry"
check "list: NULL argument is rejected" \
    "SELECT count(*) FROM arena_scan(NULL::VARCHAR[])" "path argument must not be NULL"
check "list: a directory element expands, like the scalar form" \
    "SELECT count(*)::BIGINT AS r FROM arena_scan(['$SEGDIR'])" "18"

# --- Cardinality: the planner must be told how many rows a scan returns.
# Without a cardinality callback DuckDB assumes a table function returns ONE row
# (LogicalGet::EstimateCardinality returns 1), which silently wrecks any plan built on it — an
# ASOF JOIN between two arena tables degraded to a NESTED_LOOP_JOIN because DuckDB switches to a
# loop join when the probe side is under asof_loop_join_threshold (64), and "1" always is. On
# 105k x 1M rows that was 52s instead of 0.07s.
card=$(DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" \
    "EXPLAIN SELECT * FROM arena_scan('$SEGDIR')" 2>&1 | grep -oE '~[0-9]+ rows' | head -1)
if [[ "$card" == "~18 rows" ]]; then
    echo "  [PASS] cardinality reported to the planner (18 rows, not the default 1)"
else
    echo "  [FAIL] cardinality estimate (got '$card', expected '~18 rows')"; fail=1
fi

zout=$(ARENA_SCAN_DEBUG=1 DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" \
    "SELECT count(*) FROM arena_scan('$SEGDIR') WHERE i64 > 3500000" 2>&1)
if grep -qF "kept 3 of 6" <<<"$zout"; then
    echo "  [PASS] zone-map prunes 3 of 6 sealed batches"
else
    echo "  [FAIL] zone-map pruning (expected 'kept 3 of 6')"; fail=1
fi

serial=$(DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" "PRAGMA threads=1" \
    "SELECT sum(i64)::BIGINT FROM arena_scan('$SEGDIR')" 2>&1 | tail -1)
parallel=$(DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" "PRAGMA threads=8" \
    "SELECT sum(i64)::BIGINT FROM arena_scan('$SEGDIR')" 2>&1 | tail -1)
if [[ -n "$serial" && "$serial" == "$parallel" ]]; then
    echo "  [PASS] parallel result matches serial ($serial)"
else
    echo "  [FAIL] parallel determinism (serial=$serial parallel=$parallel)"; fail=1
fi
rm -rf "$SEGDIR"

# --- D5: arena_dir setting + replacement scan (SELECT * FROM <table>) ---
BASE=$(mktemp -d)
mkdir -p "$BASE/quotes"
cp "$G/all_types.bin" "$BASE/quotes/20260101.0.arena"
cp "$G/all_types.bin" "$BASE/quotes/20260101.1.arena"

rs_count=$(DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" "SET arena_dir='$BASE'" \
    "SELECT count(*)::BIGINT FROM quotes" 2>&1 | tail -1)
if [[ "$rs_count" == "12" ]]; then
    echo "  [PASS] replacement scan: SELECT FROM quotes (2 segments = 12 rows)"
else
    echo "  [FAIL] replacement scan (got '$rs_count', expected 12)"; fail=1
fi

rs_push=$(DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" "SET arena_dir='$BASE'" \
    "SELECT count(*)::BIGINT FROM quotes WHERE i8 >= 4" 2>&1 | tail -1)
if [[ "$rs_push" == "4" ]]; then
    echo "  [PASS] replacement scan: pushdown applies (WHERE i8>=4)"
else
    echo "  [FAIL] replacement scan pushdown (got '$rs_push', expected 4)"; fail=1
fi

rs_env=$(ARENA_DIR="$BASE" DUCKDB="$DUCKDB" "$HERE/scripts/run_duckdb.sh" "LOAD '$EXT'" \
    "SELECT count(*)::BIGINT FROM quotes" 2>&1 | tail -1)
if [[ "$rs_env" == "12" ]]; then
    echo "  [PASS] arena_dir default from \$ARENA_DIR env"
else
    echo "  [FAIL] arena_dir env var (got '$rs_env')"; fail=1
fi
rm -rf "$BASE"

if [[ $fail -eq 0 ]]; then
    echo "extension tests: all passed"
else
    echo "extension tests: FAILURES"
    exit 1
fi
