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

if [[ $fail -eq 0 ]]; then
    echo "extension tests: all passed"
else
    echo "extension tests: FAILURES"
    exit 1
fi
