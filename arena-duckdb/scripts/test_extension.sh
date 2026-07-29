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

if [[ $fail -eq 0 ]]; then
    echo "extension tests: all passed"
else
    echo "extension tests: FAILURES"
    exit 1
fi
