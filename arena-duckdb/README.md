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
| Schema decode (`src/format/schema.*`) — nanoarrow IPC decode of the embedded Arrow schema → logical types + `arena.*` metadata | Not started (D2 tail) |
| DuckDB extension (`src/scan/`) — `arena_scan` table function, vector mapping, zone-map pushdown, replacement scan | Not started (D3–D6) |
| Golden expected-value CSVs (`conformance/expected/`, plan §7) | Deferred to D3 — the CSV formatting is coupled to the DuckDB type-mapping decisions (ns timestamps, `TIME`, decimals) that D3 locks |

### The reader core (`src/format/`) — done

`src/format/` is "the movable libarena-reader": pure C++20, **no DuckDB dependency**, no external
libraries (SHA-256 is vendored). It implements `docs/segment-format.md` exactly:

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

## Building the DuckDB extension (D3+, future)

Not yet scaffolded here. The next session:

1. `git submodule add -b v1.5.5 https://github.com/duckdb/duckdb.git duckdb` (pin the tag; the same
   engine as the Flight server's `duckdb_jdbc` 1.5.5.0).
2. Scaffold from `duckdb/extension-template` (CMake). **Requires `cmake`** (not present in the dev
   image used for the reader-core build — install before D1's extension build).
3. Vendor nanoarrow 0.8.0 for the embedded-schema decode, implement `src/format/schema.*`, then
   `src/scan/`.
4. Load unsigned: `duckdb -unsigned` (CLI) or `allow_unsigned_extensions=true` (`duckdb_jdbc`).

The reader core built here is the foundation all of that sits on: the DuckDB scan turns
`SegmentReader` batches into DuckDB vectors, and the zone-map pushdown filters on the catalog
min/max the snapshot already exposes.
