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
| **Reader core** (`src/format/`) — mmap, header + schema-hash verify, layout decode, catalog snapshot (acquire loads), physical value accessors, segment discovery, **schema decode** (nanoarrow IPC → logical types + `arena.*` metadata) | **Implemented & tested** (15 conformance tests green against `conformance/golden/*.bin`) |
| **`arena_scan(path)`** (`src/scan/`) — columnar table function: dynamic schema from the decoded logical types, all 13 v1 types (incl. ns timestamps, `DECIMAL(p,s)`, unsigned ints, varlen), null validity, and live in-progress batches | **Implemented & tested** (16 SQL tests green via `scripts/test_extension.sh`) |
| `arena_segments(path)` — batch-catalog diagnostic table function | **Implemented & tested** |
| Filter (zone-map) / projection pushdown, parallel scan, zero-copy vector wrapping | Not started (D4) — `arena_scan` v1 is sequential, correctness-first (copy per chunk) |
| Replacement scan + `arena_dir` setting, live-writer integration | Not started (D5) |
| Golden expected-value CSVs (`conformance/expected/`, plan §7) → sqllogictest conformance diff | Not started — value correctness is currently pinned by the SQL tests in `scripts/test_extension.sh` |

### The DuckDB extension — building & running

Two table functions, both working today:

- **`arena_scan(path)`** — the columnar scan. `SELECT * FROM arena_scan('…')` returns the segment's
  rows with the correct DuckDB types (the schema is decoded from the segment), including live
  in-progress batches. Full SQL applies: projections, `WHERE`, joins, aggregations.
- **`arena_segments(path)`** — the batch-catalog diagnostic: one row per (segment, batch) with row
  counts, sealed flag, catalog zone-map stats, seal time, and the live heartbeat.

```sh
# Build the loadable extension against a local DuckDB build (see Environment note below).
DUCKDB=/path/to/duckdb ./scripts/build_extension.sh

# Query arena data through DuckDB SQL (via the libduckdb.so host harness).
DUCKDB=/path/to/duckdb ./scripts/run_duckdb.sh \
    "LOAD '$(pwd)/build/arena.duckdb_extension'" \
    "SELECT sym, i64, ts FROM arena_scan('../conformance/golden/all_types.bin') WHERE i8 >= 3"

# Regression suite (build + SQL checks against the golden corpus).
DUCKDB=/path/to/duckdb ./scripts/test_extension.sh
```

### Environment / build model (important)

The extension is built as a **standalone loadable** against an existing DuckDB build — DuckDB is
**not** rebuilt. Key facts discovered while wiring this up (they explain the scripts):

- **Version:** built against DuckDB **v1.5.5** (the plan's pinned version, matching `duckdb_jdbc`
  1.5.5.0). The footer version string DuckDB validates is a release tag (`v1.5.5`) for release
  builds but the git hash for dev builds, so `build_extension.sh` reads it straight from a footer
  DuckDB itself stamped (its built-in parquet extension) rather than guessing.
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

## Next

`arena_scan` v1 is a correct, sequential scan (copy per chunk). The remaining milestones:

- **D4 — pushdown + parallelism:** projection column-ids, filter pushdown mapped onto the catalog
  zone maps (`arena.time_column`/`stats_column` min-max, already decoded) to skip sealed batches,
  a parallel per-(segment,batch) work list, and zero-copy vector wrapping (`FlatVector::SetData`
  over the mmap with a mapping-pinning `VectorBuffer`).
- **D5 — replacement scan + `arena_dir`:** so `SELECT * FROM quotes` resolves to
  `arena_scan('<arena_dir>/quotes')`, plus live-writer integration (query the demo writer's
  segments as it appends).
- **§7 conformance CSVs:** language-neutral expected values + a sqllogictest diff
  (`arena_scan(...) EXCEPT ALL read_csv(...)`); value correctness is currently pinned by the SQL
  assertions in `scripts/test_extension.sh`.

The reader core built here is the foundation all of that sits on: the DuckDB scan turns
`SegmentReader` batches into DuckDB vectors, and the zone-map pushdown filters on the catalog
min/max the snapshot already exposes.
