#!/usr/bin/env bash
# Builds the arena DuckDB extension as a standalone loadable (.duckdb_extension) against an existing
# DuckDB build, without rebuilding DuckDB. Loadable extensions resolve DuckDB symbols from the host
# at dlopen, so we don't link libduckdb; we only need matching compile flags (C++17, default ABI) and
# the include set DuckDB used, both reused from the local build for an exact match. The DuckDB
# metadata footer is appended with DuckDB's own append_metadata.cmake.
#
# Usage: DUCKDB=/path/to/duckdb ./scripts/build_extension.sh
set -euo pipefail

DUCKDB="${DUCKDB:-/home/goldv/src/duckdb}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$HERE/build"
mkdir -p "$BUILD"

DUCKDB_BUILD="$DUCKDB/build/release"
PARQUET_FLAGS="$DUCKDB_BUILD/extension/parquet/CMakeFiles/parquet_extension.dir/flags.make"
if [[ ! -f "$PARQUET_FLAGS" ]]; then
    echo "error: expected a built DuckDB at $DUCKDB_BUILD (parquet flags.make not found)" >&2
    exit 1
fi

# Reuse DuckDB's own include set (absolute -I paths) so headers and ABI match exactly.
INCLUDES=$(grep -oE '\-I[^ ]+' "$PARQUET_FLAGS" | tr '\n' ' ')
CXXFLAGS="-std=c++17 -O3 -DNDEBUG -fPIC -fno-rtti -Wall"

OBJS=()
for src in "$HERE"/src/format/*.cpp "$HERE"/src/scan/*.cpp; do
    obj="$BUILD/$(basename "${src%.cpp}").o"
    # shellcheck disable=SC2086
    g++ $CXXFLAGS -I"$HERE/src" $INCLUDES -c "$src" -o "$obj"
    OBJS+=("$obj")
done

EXT="$BUILD/arena.duckdb_extension"
g++ -shared -o "$EXT" "${OBJS[@]}"

# Footer must carry the running DuckDB's version (source_id) so the host accepts a CPP-ABI load.
VERSION_FIELD=$("$DUCKDB_BUILD/duckdb" -noheader -csv -c "SELECT source_id FROM pragma_version();" | tr -d '\r\n ')
cmake -DABI_TYPE=CPP -DEXTENSION="$EXT" \
    -DPLATFORM_FILE="$DUCKDB_BUILD/duckdb_platform_out" \
    -DVERSION_FIELD="$VERSION_FIELD" -DEXTENSION_VERSION="v0.1.0" \
    -DNULL_FILE="$DUCKDB/scripts/null.txt" \
    -P "$DUCKDB/scripts/append_metadata.cmake"

echo "built $EXT (duckdb $VERSION_FIELD, platform $(cat "$DUCKDB_BUILD/duckdb_platform_out"))"
