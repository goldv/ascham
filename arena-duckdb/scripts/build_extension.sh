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
NANO_INC="$HERE/src/vendor/nanoarrow/include"
CXXFLAGS="-std=c++17 -O3 -DNDEBUG -fPIC -fno-rtti -Wall"

OBJS=()
# Reader core + extension (C++), against DuckDB's headers and the vendored nanoarrow headers.
for src in "$HERE"/src/format/*.cpp "$HERE"/src/scan/*.cpp; do
    obj="$BUILD/$(basename "${src%.cpp}").o"
    # shellcheck disable=SC2086
    g++ $CXXFLAGS -I"$HERE/src" -I"$NANO_INC" $INCLUDES -c "$src" -o "$obj"
    OBJS+=("$obj")
done
# Vendored nanoarrow (C) — the embedded-schema IPC decoder used by src/format/schema.cpp.
for src in "$HERE"/src/vendor/nanoarrow/src/*.c; do
    obj="$BUILD/$(basename "${src%.c}").o"
    gcc -std=c11 -O3 -DNDEBUG -fPIC -I"$NANO_INC" -c "$src" -o "$obj"
    OBJS+=("$obj")
done

EXT="$BUILD/arena.duckdb_extension"
g++ -shared -o "$EXT" "${OBJS[@]}"

# The footer must carry the exact version string this DuckDB build validates against. That is a
# release tag ("v1.5.5") for release builds but the git hash for dev builds — so read it straight
# from a footer DuckDB itself stamped (its built-in parquet extension) rather than guessing.
PARQUET_EXT="$DUCKDB_BUILD/extension/parquet/parquet.duckdb_extension"
VERSION_FIELD=$(tail -c 512 "$PARQUET_EXT" | strings | head -1 | tr -d '\r\n ')
if [[ -z "$VERSION_FIELD" ]]; then
    echo "error: could not read DuckDB version from $PARQUET_EXT footer" >&2
    exit 1
fi
cmake -DABI_TYPE=CPP -DEXTENSION="$EXT" \
    -DPLATFORM_FILE="$DUCKDB_BUILD/duckdb_platform_out" \
    -DVERSION_FIELD="$VERSION_FIELD" -DEXTENSION_VERSION="v0.1.0" \
    -DNULL_FILE="$DUCKDB/scripts/null.txt" \
    -P "$DUCKDB/scripts/append_metadata.cmake"

echo "built $EXT (duckdb $VERSION_FIELD, platform $(cat "$DUCKDB_BUILD/duckdb_platform_out"))"
