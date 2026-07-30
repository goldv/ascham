# arena-duckdb

Native C++ DuckDB extension for querying [arena](../docs/arena-design-plan.md) shared-memory
columnar segments directly — with projection/filter pushdown and a replacement scan so arena tables
are queryable by name. See [`docs/duckdb-extension-design-plan.md`](../docs/duckdb-extension-design-plan.md)
for the full design (milestones D1–D6), and
[`docs/code-walkthrough.md`](docs/code-walkthrough.md) for an annotated tour of the source (aimed at
readers newer to C++ / DuckDB internals).

This directory is deliberately **standalone** — its only coupling to the rest of the repo is
read-only (`docs/segment-format.md` as the byte contract, `conformance/` as the golden corpus). That
is what makes the planned move to its own repository a directory copy.

## Status

| Layer | State |
|---|---|
| **Reader core** (`src/format/`) — mmap, header + schema-hash verify, layout decode, catalog snapshot (acquire loads), physical value accessors, segment discovery, **schema decode** (nanoarrow IPC → logical types + `arena.*` metadata) | **Implemented & tested** (15 conformance tests green against `conformance/golden/*.bin`) |
| **`arena_scan(path)`** (`src/scan/`) — columnar table function: dynamic schema from the decoded logical types, all 13 v1 types (incl. ns timestamps, `DECIMAL(p,s)`, unsigned ints, varlen), null validity, and live in-progress batches | **Implemented & tested** (21 SQL tests green via `scripts/test_extension.sh`) |
| **D4 — pushdown + parallelism:** projection pushdown, filter pushdown (row-exact via DuckDB's own applicator) with **zone-map batch pruning** on the catalog `time`/`stats` min-max, and a parallel per-(segment,batch) work list | **Implemented & tested** |
| `arena_segments(path)` — batch-catalog diagnostic table function | **Implemented & tested** |
| **D5 — replacement scan + `arena_dir` setting:** `SELECT * FROM <table>` resolves to `arena_scan('<arena_dir>/<table>')` when that table dir holds segments (pushdown flows through), with `arena_dir` defaulting from `$ARENA_DIR`; plus live-writer integration (`scripts/live_demo.sh`) — a Java writer appends while DuckDB queries the growing, in-progress data | **Implemented & tested** |
| Zero-copy vector wrapping (`FlatVector::SetData` over the mmap) | Not started — `arena_scan` fills by copy per chunk (correct; a perf optimization remains) |
| Golden expected-value CSVs (`conformance/expected/`, plan §7) → sqllogictest conformance diff | Not started — value correctness is currently pinned by the SQL tests in `scripts/test_extension.sh` |

### The DuckDB extension — building & running

Two table functions, both working today:

- **`arena_scan(path)`** — the columnar scan. `SELECT * FROM arena_scan('…')` returns the segment's
  rows with the correct DuckDB types (the schema is decoded from the segment), including live
  in-progress batches. Full SQL applies: projections, `WHERE`, joins, aggregations. `path` is a
  segment file or a table directory (all its segments, oldest-first).
- **`arena_scan([path, …])`** — the same scan over an explicit list of segments, each element
  resolved by the same rule. Use it when the caller must know exactly which segments were read —
  the cold-tier roll archives and then unlinks the same list, with no directory re-listing in
  between. Duplicate entries, empty lists, and NULLs are rejected rather than silently tolerated.
- **`arena_segments(path)`** — the batch-catalog diagnostic: one row per (segment, batch) with row
  counts, sealed flag, catalog zone-map stats, seal time, and the live heartbeat.

```sh
# Build the loadable extension against a local DuckDB build (see Environment note below).
DUCKDB=/path/to/duckdb ./scripts/build_extension.sh

# It's a self-contained extension, so it loads in the stock DuckDB CLI like any other:
duckdb -unsigned -c "LOAD '$(pwd)/build/arena.duckdb_extension';
                     SELECT sym, i64 FROM arena_scan('../conformance/golden/all_types.bin')"
# ...and in Python: duckdb.connect(config={'allow_unsigned_extensions':'true'}).load_extension(path)

# Query arena data through DuckDB SQL (via the libduckdb.so host harness).
DUCKDB=/path/to/duckdb ./scripts/run_duckdb.sh \
    "LOAD '$(pwd)/build/arena.duckdb_extension'" \
    "SELECT sym, i64, ts FROM arena_scan('../conformance/golden/all_types.bin') WHERE i8 >= 3"

# Or from Python via the standard `duckdb` library — see python/ (a uv project pinned to
# duckdb==1.5.5; renders all types incl. ns timestamps).
cd python && uv run arena_query.py \
    "SELECT sym, i64, ts FROM arena_scan('../../conformance/golden/all_types.bin') WHERE i8 >= 3"

# Regression suite (build + SQL checks against the golden corpus).
DUCKDB=/path/to/duckdb ./scripts/test_extension.sh

# Live demo: a Java writer appends mock quotes while DuckDB queries the growing table
# (SELECT * FROM quotes resolves via the replacement scan + arena_dir). Shows freshness.
DUCKDB=/path/to/duckdb ./scripts/live_demo.sh
```

The replacement scan makes an arena table queryable by name — set `arena_dir` (or `$ARENA_DIR`) to
the base directory and `SELECT * FROM quotes` scans `<arena_dir>/quotes/`, with projection/filter
pushdown flowing through unchanged:

```sh
DUCKDB=/path/to/duckdb ARENA_DIR=/dev/shm/ito ./scripts/run_duckdb.sh \
    "LOAD '$(pwd)/build/arena.duckdb_extension'" \
    "SELECT sym, count(*), max(px) FROM quotes GROUP BY sym"
```

### Environment / build model (important)

The extension is built as a **standalone loadable** against an existing DuckDB build — DuckDB is
**not** rebuilt. Key facts discovered while wiring this up (they explain the scripts):

- **Version:** built against DuckDB **v1.5.5** (the plan's pinned version, matching `duckdb_jdbc`
  1.5.5.0). The footer version string DuckDB validates is a release tag (`v1.5.5`) for release
  builds but the git hash for dev builds, so `build_extension.sh` reads it straight from a footer
  DuckDB itself stamped (its built-in parquet extension) rather than guessing.
- **Self-contained (static link):** the extension **statically links** the DuckDB code it calls
  (`libduckdb_static.a` + third-party archives + the dummy extension-loader stub), so it has **zero
  undefined `duckdb::` symbols**. That is what official extensions do (json is ~34 MB for the same
  reason), and it is what makes it load *anywhere* — the DuckDB CLI, the Python `duckdb` module, or
  any host — because it asks the host for no symbols at `dlopen`. Compiled with DuckDB's own include
  set and flags (C++17, `-O3 -fPIC`) for an exact ABI match. *(A thin `-shared` build with no static
  link is ~100× smaller but only loads into a host that exports DuckDB's symbols in the global scope
  — e.g. an app that links `libduckdb.so` — and fails in the CLI and in Python's `RTLD_LOCAL`-loaded
  module with an `undefined symbol` error.)*
- **`-fno-rtti`:** DuckDB is built with RTTI but its unused class typeinfos are GC'd, so a default
  (RTTI) build references typeinfo symbols not present; `-fno-rtti` drops those references, and
  DuckDB's `Cast<>` is `static_cast`, so this is safe.
- **Hosts:** because the extension is self-contained it loads in the **DuckDB CLI**
  (`duckdb -unsigned`), the **Python `duckdb` module** (`con.load_extension(...)`, no dlopen hacks),
  and any app linking `libduckdb.so`. `test/host/duckdb_host.c` (built against `libduckdb.so`, the
  same library `duckdb_jdbc` uses) is the C test host; `python/` (a uv project on the pinned
  `duckdb` library) is the Python client.
  `-unsigned` / `allow_unsigned_extensions=true` is required for an unsigned local build.
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

`arena_scan` does projection + filter pushdown (with zone-map batch pruning) and parallel scans.
The remaining milestones:

- **Zero-copy vector wrapping:** `arena_scan` currently fills DuckDB vectors by copy per chunk
  (correct). Wrapping the mapped buffers directly (`FlatVector::SetData` with a mapping-pinning
  `VectorBuffer`) removes the copy for the fixed-width/validity paths.
- **§7 conformance CSVs:** language-neutral expected values + a sqllogictest diff
  (`arena_scan(...) EXCEPT ALL read_csv(...)`); value correctness is currently pinned by the SQL
  assertions in `scripts/test_extension.sh`.

The reader core built here is the foundation all of that sits on: the DuckDB scan turns
`SegmentReader` batches into DuckDB vectors, and the zone-map pushdown filters on the catalog
min/max the snapshot already exposes.
