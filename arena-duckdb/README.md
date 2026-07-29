# arena-duckdb

Native C++ DuckDB extension for querying [arena](../docs/arena-design-plan.md) shared-memory
columnar segments directly — with projection/filter pushdown and a replacement scan so arena tables
are queryable by name. See [`docs/duckdb-extension-design-plan.md`](../docs/duckdb-extension-design-plan.md)
for the full design (milestones D1–D6).

This directory is deliberately **standalone** — its only coupling to the rest of the repo is
read-only (`docs/segment-format.md` as the byte contract, `conformance/` as the golden corpus). That
is what makes the planned move to its own repository a directory copy.

## Status

| Layer | State |
|---|---|
| **Reader core** (`src/format/`) — mmap, header + schema-hash verify, layout decode, catalog snapshot (acquire loads), physical value accessors, segment discovery | **Implemented & tested** (12 conformance tests green against `conformance/golden/*.bin`) |
| **DuckDB extension toolchain** — loadable `.duckdb_extension` builds, footers, and loads; `arena_segments()` diagnostic table function | **Implemented & tested** (7 SQL tests green via `scripts/test_extension.sh`) |
| Schema decode (`src/format/schema.*`) — nanoarrow IPC decode of the embedded Arrow schema → logical types + `arena.*` metadata | Not started (needed by `arena_scan`) |
| `arena_scan` (`src/scan/`) — dynamic-schema zero-copy table function, projection/filter (zone-map) pushdown, replacement scan | Not started (D3–D5) |
| Golden expected-value CSVs (`conformance/expected/`, plan §7) | Deferred to `arena_scan` — the CSV formatting is coupled to the DuckDB type-mapping decisions (ns timestamps, `TIME`, decimals) locked there |

### The DuckDB extension — building & running

`arena_segments(path)` exposes the batch catalog of a segment file (or a table directory) as SQL:
one row per (segment, batch) with row counts, sealed flag, catalog zone-map stats, seal time, and
the live heartbeat. It is the reader core surfaced through DuckDB, and it works today:

```sh
# Build the loadable extension against a local DuckDB build (see Environment note below).
DUCKDB=/path/to/duckdb ./scripts/build_extension.sh

# Query a golden segment through DuckDB SQL (via the libduckdb.so host harness).
DUCKDB=/path/to/duckdb ./scripts/run_duckdb.sh \
    "LOAD '$(pwd)/build/arena.duckdb_extension'" \
    "SELECT batch, rows, sealed, ts_min, ts_max, stat_max FROM arena_segments('../conformance/golden/all_types.bin')"

# Regression suite (build + SQL checks against the golden corpus).
DUCKDB=/path/to/duckdb ./scripts/test_extension.sh
```

### Environment / build model (important)

The extension is built as a **standalone loadable** against an existing DuckDB build — DuckDB is
**not** rebuilt. Key facts discovered while wiring this up (they explain the scripts):

- **Version:** the local DuckDB is on `main` (`v1.6.0-dev`, source_id `ee6ce3cd2d`), not the plan's
  pinned v1.5.5. The extension is built against and loads into *this* build (versions match by the
  footer's source_id). Loading into `duckdb_jdbc` 1.5.5.0 (the Flight server target) needs a
  version-matched build — a D6 concern.
- **No DuckDB link:** loadable extensions resolve DuckDB symbols from the host at `dlopen`, so we
  compile + link a plain shared object (`-shared`, no `libduckdb`), reusing DuckDB's own include set
  and flags (C++17, `-O3 -fPIC`) for an exact ABI match.
- **`-fno-rtti`:** DuckDB is built with RTTI but its unused class typeinfos are GC'd from the binary,
  so a default (RTTI) build references symbols the host doesn't provide. Building the extension
  `-fno-rtti` drops those references; DuckDB's `Cast<>` is `static_cast`, so this is safe.
- **Host via `libduckdb.so`:** the CLI binary is statically linked and exports **no** symbols, so it
  cannot host a loadable (its built-in extensions are linked in, not `dlopen`ed). `libduckdb.so`
  exports the full API, so `test/host/duckdb_host.c` (built against it, the same library
  `duckdb_jdbc` uses) is the test host. `-unsigned` / `allow_unsigned_extensions=true` is required.
- **Footer:** DuckDB's `scripts/append_metadata.cmake` writes the loadable metadata footer
  (platform `linux_amd64`, ABI `CPP`, version = the build's source_id).

### The reader core (`src/format/`) — done

`src/format/` is "the movable libarena-reader": portable C++17, **no DuckDB dependency**, no external
libraries (SHA-256 is vendored). Compiles standalone (its own Makefile) and also inside the DuckDB
extension. It implements `docs/segment-format.md` exactly:

- `mapped_file` — read-only `MAP_SHARED` mmap held for the reader's lifetime.
- `segment_reader` — verifies magic/version and **recomputes the schema SHA-256 to reject a
  mismatch (invariant 7)**; decodes the layout descriptor; freezes a catalog snapshot with
  **acquire loads** on `active_batch_count` and each `length` (the C++ side of the concurrency
  contract); exposes physical column values (fixed / bool-bitmap / varlen, with validity).
- `layout` — LayoutCodec (codec version 1) decoder.
- `table_dir` — `<yyyyMMdd>.<seq>.arena` discovery, oldest-first, skipping `*.tmp.*`.

The tests validate decoded values against the deterministic golden data defined in the Java
`GoldenCases` — so a passing run proves the C++ and Java readers agree on the byte format, including
that the vendored SHA-256 matches Java's (every golden open would fail otherwise). Coverage: every
supported type, nulls, empty batch, in-progress batch, varlen empty/exact-capacity+migration,
FixedSizeBinary widths {1,7,16,33}, type bounds (Decimal128 ±(2¹²⁷−1), unsigned maxima), and
corrupt-magic/hash hard failures.

## Build & test (reader core)

Requires only a C++20 compiler (tested with g++ 15):

```sh
make test            # builds src/format/ + test/cpp/ and runs the conformance suite
```

## Next: `arena_scan`

`arena_segments` proves the reader core ↔ DuckDB bridge with a fixed scalar schema. The headline
table function `arena_scan(path)` — dynamic schema, **zero-copy** vectors over the mapped segment,
projection/filter (zone-map) pushdown, and a replacement scan so `SELECT * FROM quotes` just works —
is next. It needs the one missing reader-core piece: **schema decode** (`src/format/schema.*`),
vendoring nanoarrow 0.8.0 to turn the embedded Arrow IPC schema into logical types + `arena.*`
metadata (the layout descriptor gives physical kind/width but not logical type). Then the plan's §7
expected-value CSVs and the sqllogictest conformance diff (`arena_scan(...) EXCEPT ALL read_csv(...)`)
land, followed by pushdown/parallelism (D4) and the live-writer integration (D5).

The reader core built here is the foundation all of that sits on: the DuckDB scan turns
`SegmentReader` batches into DuckDB vectors, and the zone-map pushdown filters on the catalog
min/max the snapshot already exposes.
