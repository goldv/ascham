#!/usr/bin/env bash
# Builds the arena DuckDB extension as a self-contained loadable (.duckdb_extension) against an
# existing DuckDB build, without rebuilding DuckDB. We compile with DuckDB's own include set and
# flags (C++17, -fPIC, -fno-rtti) for an exact ABI match, then **statically link** the DuckDB code
# we call (libduckdb_static.a + its third-party archives + the dummy extension-loader stub). That
# leaves the extension with zero undefined `duckdb::` symbols, so it is fully self-contained and
# loads like any official extension — in the DuckDB CLI, in the Python `duckdb` module (no
# RTLD_GLOBAL needed), or any host — because it asks the host for no symbols at dlopen. (A thin
# build that omits the static link is ~100x smaller but only loads into a host that exports DuckDB's
# symbols globally, e.g. an app linking libduckdb.so; it fails in the CLI and in Python's
# RTLD_LOCAL-loaded module.) The DuckDB metadata footer is appended with append_metadata.cmake.
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
# Statically link the DuckDB code we reference so the extension is self-contained (0 undefined
# duckdb:: symbols). --start-group/--end-group resolves cross-archive references; the linker pulls
# only the objects our 55 references need. The dummy loader stubs out ExtensionHelper::LoadAll (which
# a pulled-in object references but we never call). jemalloc is excluded so we don't override malloc.
DUCKDB_STATIC_LIBS=("$DUCKDB_BUILD/src/libduckdb_static.a"
                    "$DUCKDB_BUILD/extension/libdummy_static_extension_loader.a")
while IFS= read -r a; do DUCKDB_STATIC_LIBS+=("$a"); done \
    < <(ls "$DUCKDB_BUILD"/third_party/*/lib*.a | grep -v jemalloc)
if [[ ! -f "$DUCKDB_BUILD/src/libduckdb_static.a" ]]; then
    echo "error: $DUCKDB_BUILD/src/libduckdb_static.a not found (build DuckDB with the static lib)" >&2
    exit 1
fi
g++ -shared -o "$EXT" "${OBJS[@]}" -Wl,--start-group "${DUCKDB_STATIC_LIBS[@]}" -Wl,--end-group

undefined=$(nm -D -u "$EXT" 2>/dev/null | grep -c '6duckdb' || true)
if [[ "$undefined" != "0" ]]; then
    echo "warning: $undefined undefined duckdb symbols remain — extension may not load in all hosts" >&2
fi

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
