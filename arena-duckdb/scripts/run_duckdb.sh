#!/usr/bin/env bash
# Runs SQL through a libduckdb.so-hosted DuckDB (builds the tiny host harness on demand). Use to load
# the arena extension and query it, e.g.:
#   scripts/run_duckdb.sh "LOAD '$(pwd)/build/arena.duckdb_extension'" "SELECT * FROM arena_segments('...')"
set -euo pipefail

DUCKDB="${DUCKDB:-/home/goldv/src/duckdb}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LIBDIR="$DUCKDB/build/release/src"
HOST="$HERE/build/duckdb_host"
mkdir -p "$HERE/build"

if [[ ! -x "$HOST" || "$HERE/test/host/duckdb_host.c" -nt "$HOST" ]]; then
    gcc "$HERE/test/host/duckdb_host.c" -I"$DUCKDB/src/include" \
        -L"$LIBDIR" -lduckdb -Wl,-rpath,"$LIBDIR" -rdynamic -O2 -Wall -Wno-dangling-pointer -o "$HOST"
fi

exec "$HOST" "$@"
