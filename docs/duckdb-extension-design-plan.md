# `arena` DuckDB extension (native C++) — design & implementation plan

Companion to [`segment-format.md`](segment-format.md) (the byte contract this reader implements),
[`arena-design-plan.md`](arena-design-plan.md), and
[`flight-sql-design-plan.md`](flight-sql-design-plan.md). This document designs the **native C++
DuckDB extension** anticipated by the original arena spec ("the C++ reader and DuckDB table
function"): a table function `arena_scan` reading arena segments directly from `/dev/shm`, with
**full projection and filter pushdown**, plus a replacement scan so arena tables are queryable by
name.

**Why this is the higher-value tier right now:**

1. **Zero-server SQL access, immediately.** `duckdb -unsigned` in a terminal, or DuckDB in Python/
   a notebook, queries live arena tables in shared memory directly — joins, aggregations, window
   functions over data the Java writer is appending *right now*. No Flight server required.
2. **The Flight SQL server inherits pushdown for free.** The server design's known v1 gap (fixed
   `registerArrowStream` streams → full scans) is closed by `LOAD`-ing this same extension into the
   server's embedded DuckDB and querying `arena_scan` tables instead of registered streams. One
   binary, both consumers — the server's engine (`duckdb_jdbc` 1.5.5.0) and the current DuckDB
   release (v1.5.5) are the same engine version.
3. **It cashes in the format's design collateral.** Segments are self-describing (embedded schema +
   layout descriptor — "readers need no build-time coupling"), every buffer is 64-byte aligned
   ("Arrow and DuckDB readers are entitled to assume this"), and the golden corpus exists precisely
   to validate a second-language reader against the checked-in bytes.

Status: **design — implementation gated on the D1–D6 milestones below.**

Verified up-front (this session): DuckDB **v1.5.5** is the current release tag (matches
`duckdb_jdbc` 1.5.5.0 in the Flight server design); `duckdb/extension-template` is active;
**nanoarrow 0.8.0** is current (vendorable amalgamation; its IPC decoder parses Arrow schema
messages without an Arrow C++ dependency).

---

## 1. Decisions

1. **C++ extension API, pinned to DuckDB v1.5.5** (git submodule tag), built from
   `duckdb/extension-template`. The stable-ABI C extension API is attractive for
   version-portability but its table-function surface is not rich enough for the pushdown and
   zero-copy vector tricks this extension exists for; version pinning is the price of full
   pushdown, and the pin already matches the Flight server's engine. Revisit C-ABI when its table
   function API matures (recorded as v2).
2. **New top-level directory `arena-duckdb/`, deliberately NOT a Gradle module.** It is a
   self-contained CMake/Make project (the extension-template layout) whose only coupling to the
   rest of the repo is read-only: `docs/segment-format.md` (the contract), `conformance/` (the
   golden corpus), and — for live tests — segments produced by the Java demo writer. This is what
   makes the planned move to its own repo a directory copy. An optional root-level convenience
   Gradle task may shell out to `make`; the canonical build is standalone.
3. **nanoarrow (vendored amalgamation, 0.8.0) decodes the embedded schema; everything else is
   hand-read per `segment-format.md`.** The layout descriptor gives byte offsets/kinds/widths but
   deliberately not logical types; the embedded Arrow IPC schema message provides logical types
   and the `arena.*` metadata (`time_column`, `stats_column` — needed to target zone maps).
   nanoarrow's IPC decoder turns that flatbuffer into an `ArrowSchema` C-data tree with
   key-value metadata, from which the v1 type profile (13 closed types) maps to DuckDB
   `LogicalType`s by hand. No Arrow C++, no flatbuffers toolchain — the spec's "C++ reader without
   an Arrow dependency" promise, kept.
4. **SHA-256 via a vendored single-file implementation** for the invariant-7 hash check at open
   (DuckDB bundles mbedtls but its availability to extensions is an internal detail we don't lean
   on).
5. **Snapshot semantics identical to the Java reader**: per query bind, list segments → open +
   verify all → freeze every catalog once with acquire loads → serve the frozen view. Same
   mixed-schema rejection, same tolerance of retention unlink (mmap + kernel refcount), same
   in-progress-batch visibility.
6. **Unsigned extension loading** (`duckdb -unsigned` CLI; `allow_unsigned_extensions=true`
   connection property in `duckdb_jdbc`) is the v1 distribution story. Community-extension
   signing/distribution is explicitly out of scope until the maybe-own-repo decision.

## 2. Module layout (`arena-duckdb/`)

```
arena-duckdb/
├── CMakeLists.txt / Makefile          — extension-template scaffolding
├── duckdb/                            — git submodule, tag v1.5.5
├── extension_config.cmake
├── src/
│   ├── arena_extension.cpp            — Load(): register function set, settings, replacement scan
│   ├── include/…
│   ├── format/                        — the C++ reader core (NO DuckDB types in here —
│   │   ├── segment_file.hpp/.cpp      —   this subtree is the movable "libarena-reader")
│   │   ├── segment_header.hpp/.cpp    — magic/version/region-table/hash verify (invariant 7)
│   │   ├── layout.hpp/.cpp            — LayoutCodec decoder (codec_version 1)
│   │   ├── catalog.hpp/.cpp           — catalog snapshot w/ acquire loads; bit-63 semantics
│   │   ├── schema.hpp/.cpp            — nanoarrow IPC schema decode → column types + arena.* keys
│   │   └── table_dir.hpp/.cpp         — <dir>/<yyyyMMdd>.<seq>.arena discovery (skips *.tmp.*)
│   ├── scan/
│   │   ├── arena_scan.cpp             — bind/init/scan table function (§4)
│   │   ├── vector_mapping.cpp         — arena buffers → DuckDB vectors (§5)
│   │   └── zone_map.cpp               — filter pushdown → batch/segment skipping (§6)
│   └── vendor/                        — nanoarrow amalgamation, sha256
├── test/
│   ├── sql/                           — sqllogictests (golden corpus + pushdown + errors)
│   └── cpp/                           — reader-core unit tests (header/layout/catalog decode)
└── scripts/live_demo_test.sh          — concurrent Java-writer integration check (§8)
```

The `format/` subtree is kept free of DuckDB headers on purpose: it is the reusable C++ arena
reader, and the future standalone-repo split (or a non-DuckDB C++ consumer) lifts it unchanged.

## 3. Concurrency: the C++ side of the segment contract

Implements `segment-format.md` §"Concurrency contract" exactly:

- **Acquire loads** for `active_batch_count`, each catalog `length`, and `heartbeat`:
  `std::atomic_ref<const int64_t>(…).load(std::memory_order_acquire)` over the mapped memory
  (all ordered fields are 8-aligned by format construction; `atomic_ref` alignment requirements
  are satisfied). Everything else is plain loads — made visible by the acquire, same reasoning as
  the Java `ControlRegion`.
- **Snapshot = freeze once**: read `active_batch_count`, then each `length` exactly once; row
  count = `length & ~(1<<63)`, sealed = bit 63 clear. Never re-read (a stale snapshot is safe, an
  inconsistent one is not). Stats/`seal_nanos` are trusted only for sealed entries; in-progress
  batches are never zone-map-skipped.
- **mmap read-only**, `MAP_SHARED`; mappings held for the whole query (bind→global state), so a
  concurrent retention `unlink` is harmless (kernel refcount — the format's documented reclamation
  semantics). A file that vanishes between directory listing and `open()` is skipped.
- Little-endian throughout — matches x86-64/aarch64 hosts; no byte swapping (asserted at compile
  time).

## 4. The `arena_scan` table function

Surface registered by the extension:

| SQL surface | Purpose |
|---|---|
| `arena_scan('<table>')` | scan a table under the configured `arena_dir` |
| `arena_scan('<path>/…/x.arena')` (file) / `('<dir>')` | scan an explicit segment file or table directory — this is how conformance tests read `conformance/golden/*.bin` |
| `SET arena_dir = '/dev/shm/ito'` | extension setting (also readable from env `ARENA_DIR`) |
| replacement scan | unknown identifier `quotes` → `arena_scan('quotes')` when `<arena_dir>/quotes` exists — makes `SELECT * FROM quotes` just work (CLI, Python, and the Flight server alike) |
| `arena_segments('<table>')` | diagnostic: one row per segment × batch — day, seq, path, batch index, rows, sealed, ts/stat min-max, seal_nanos, writer epoch, heartbeat — liveness and layout visibility from SQL |

**Bind** (once per query): resolve table → list segment files (oldest-first by `(day, seq)`) →
open + verify each (magic, format version, schema-hash recompute over the embedded schema bytes —
invariant 7, hard failure) → decode layout descriptor + schema → require identical schema across
segments (else clear error, as the Java reader) → freeze all catalog snapshots → return column
names/`LogicalType`s. Bind data owns the mappings and frozen snapshots for the query's lifetime.

**Init / parallelism**: the work unit is one (segment, batch) pair. Global init builds the flat
work list *after zone-map filtering* (§6); `max_threads = work_list.size()`. Each local state
claims work items via an atomic cursor — DuckDB's standard parallel-scan shape; a segment's
batches scan in parallel with zero coordination because sealed bytes are immutable and the
in-progress batch's row count was frozen at bind.

**Scan**: each work item emits its batch as ⌈rows / 2048⌉ `DataChunk`s (`STANDARD_VECTOR_SIZE`),
slicing per-column base pointers by row offset (§5). Only projected columns
(`TableFunctionInitInput::column_ids`) are materialized — **projection pushdown is exact and
free**.

## 5. Column mapping: arena buffers → DuckDB vectors

Per `segment-format.md`, data buffers are Arrow-format; DuckDB's physical layouts overlap heavily.
Mapping for the v1 profile (zero-copy = `FlatVector::SetData` at
`batch_base + data_offset + first_row × width`, plus a custom `VectorBuffer` subclass attached to
the vector holding a `shared_ptr` to the segment mapping — chunks may be buffered downstream by
order-preserving parallel operators, so **each emitted vector pins its mapping independently**;
this mirrors what DuckDB's own Arrow scanner does):

| Arena type | DuckDB type | Path |
|---|---|---|
| `Int8/16/32/64` | `TINYINT/SMALLINT/INTEGER/BIGINT` | zero-copy |
| `UInt8/16/32/64` | `UTINYINT/USMALLINT/UINTEGER/UBIGINT` | zero-copy |
| `Float32/64` | `FLOAT/DOUBLE` | zero-copy |
| `Date32` | `DATE` (int32 days) | zero-copy |
| `Decimal128(p,s)`, 18 < p ≤ 38 | `DECIMAL(p,s)` (hugeint-backed; same int128 LE layout) | zero-copy |
| `Decimal128(p,s)`, p ≤ 18 | `DECIMAL(p,s)` (int16/32/64-backed) | narrowing copy (DuckDB stores small decimals narrower than arena's fixed 16 B) |
| `Timestamp(ns, tz)` | `TIMESTAMP_NS` | zero-copy; **tz dropped, UTC convention documented** (DuckDB `TIMESTAMPTZ` is µs — converting would lose ns; decision D3, revisit if a µs `TIMESTAMPTZ` view is preferred) |
| `Timestamp(us, tz)` | `TIMESTAMPTZ` | zero-copy (µs native) |
| `Timestamp(us)` no tz | `TIMESTAMP` | zero-copy |
| `Time64(ns)` | `TIME` (µs) **or** `TIME_NS` if present in v1.5.5 | verify at D3; fallback is a ÷1000 converting copy, documented |
| `Bool` (bitmap) | `BOOLEAN` (1 byte/row) | converting copy (bit → byte; cheap, bitmap source) |
| `Utf8` | `VARCHAR` | header-build: one `string_t` per row from the offsets buffer — ≤12-byte values inline, longer values **point into the arena data buffer** (no data copy) with the mapping pinned via the vector's buffer handle |
| `Binary` / `FixedSizeBinary(n)` | `BLOB` | same `string_t` header-build over offsets / fixed stride |

**Validity**: arena validity bitmaps are Arrow LSB bit-per-row; DuckDB's `ValidityMask` is
bit-identical (little-endian uint64 words ≡ LSB byte order) and arena validity buffers are
64-byte aligned — **zero-copy validity** via mask pointer assignment. (All-valid fast path: skip
the mask entirely when the batch's null count is zero — cheap check while building headers.)

## 6. Filter pushdown → zone maps

`arena_scan` registers with `filter_pushdown = true`. At global init, the `TableFilterSet` is
inspected for constant comparisons / `BETWEEN` / `IS NOT NULL` on two special columns discovered
from schema metadata:

- **`arena.time_column`** → compare against catalog `ts_min`/`ts_max`,
- **`arena.stats_column`** → compare against `stat_min`/`stat_max`,

and each **sealed** batch whose `[min,max]` cannot intersect the filter is dropped from the work
list before any thread starts (in-progress batches are never skipped — their stats are unpublished
until seal, per the format doc). Segment-level short-circuit: a segment whose *every* batch is
skipped never contributes work items; day-range filters additionally prune whole segments by file
name before open where provable. All filters remain in the plan for DuckDB's row-exact
re-evaluation — zone maps are an optimization, never the semantics (same honesty rule as
everywhere else in this project). `EXPLAIN ANALYZE` visibility: skipped-batch counts exposed via
the function's cardinality/progress callbacks so pushdown effectiveness is observable.

## 7. Prerequisite on the Java side (small, same repo)

The golden corpus currently checks byte-stability + Java read-back; a second-language reader needs
**language-neutral expected values**. `GoldenCorpusGenerator` (in `:arena` test scope) gains an
`expected/` emitter: per case, `conformance/expected/<case>.csv` (header row; deterministic
formatting rules documented in the file header — hex for binary, ISO for timestamps, raw int64 for
implied-scale). The C++ conformance gate is then a DuckDB-native diff, per case:

```sql
-- generated sqllogictest, one per corpus case
SELECT count(*) FROM (
  SELECT * FROM arena_scan('conformance/golden/all_types.bin')
  EXCEPT ALL SELECT * FROM read_csv('conformance/expected/all_types.csv', …)
) t;  -- expect 0, and the symmetric EXCEPT ALL, and equal counts
```

This closes the "deferred: expected.json" note in `arena-design-plan.md` §M4 — CSV chosen over
JSON because DuckDB ingests it natively, making the conformance check a pure-SQL assertion.

## 8. Testing

- **Reader-core unit tests** (`test/cpp/`, no DuckDB): header decode/verify against golden bytes,
  layout-codec decode round-trip vs the descriptor region, catalog bit-63/row-count semantics,
  corrupted magic/hash → error (invariant 7).
- **Conformance sqllogictests** (`test/sql/`): the §7 diff per golden case — every supported type,
  nulls, varlen edge cases, in-progress batch, type bounds, read through the *native* scan.
- **Pushdown tests**: `EXPLAIN` shows projection column ids; zone-map tests build multi-batch
  segments (via the Java writer in a setup step or committed fixtures) and assert skipped-batch
  counts + result correctness with/without filters; parallel-scan correctness (result equality at
  `threads=1` vs `threads=N`).
- **Live integration** (`scripts/live_demo_test.sh`): start the Java `MarketDataWriter` (from the
  Flight plan's `:demo`) against a temp dir, loop `duckdb -unsigned` queries: monotonically
  non-decreasing `count(*)`, well-formed strings, `arena_segments` shows heartbeat advancing;
  then kill the writer mid-append and verify reads stay consistent (frozen snapshots, no torn
  values). This is the C++ twin of the Java soak's assertions.
- **Version-lock check**: extension loads in the DuckDB CLI v1.5.5 **and** in `duckdb_jdbc`
  1.5.5.0 (a tiny Java test in `:flight-server`'s suite at F-integration time, or a script here).

## 9. Milestones

| # | Deliverable | Exit criteria / tests |
|---|---|---|
| **D1** | `arena-duckdb/` scaffolded from extension-template; duckdb submodule pinned v1.5.5; empty `arena` extension builds; loads in CLI (`-unsigned`) and via `duckdb_jdbc` with `allow_unsigned_extensions` | `make` + `make test` green on the template smoke test; documented build/load runbook |
| **D2** | Reader core (`format/`): mmap + header verify + layout decode + catalog snapshot (acquire semantics) + nanoarrow schema decode (types + `arena.*` metadata); vendored sha256 | `test/cpp` unit suite green against `conformance/golden/*.bin`; corrupted-magic/hash tests |
| **D3** | `arena_scan` (file + dir forms): bind/schema mapping per §5, sequential scan, zero-copy fixed/validity paths, string_t varlen, bool/decimal conversions; **ns-timestamp + TIME mapping decisions locked** | first conformance sqllogictests green (requires §7 Java emitter, done here); `all_types`/`varlen_*`/`type_bounds` cases pass |
| **D4** | Pushdown + parallelism: projection column_ids, zone-map batch/segment skipping, parallel work list; `arena_segments` diagnostic | full golden conformance green; pushdown EXPLAIN tests; threads=1 ≡ threads=N; skip-count assertions |
| **D5** | Replacement scan + `arena_dir` setting/env; live-writer integration | `SELECT * FROM quotes` works in CLI against a running Java writer; `scripts/live_demo_test.sh` green incl. writer-kill consistency |
| **D6** | Consumption + handoff: load recipe for the Flight SQL server (LOAD + `arena_dir`, replacing `registerArrowStream` for arena tables — closes the server's pushdown gap); README; own-repo split checklist (what moves, what the conformance contract pins) | server-side smoke (JDBC connection with unsigned load executes a pruned query); docs complete |

> **Addendum (2026-07-30):** [`cold-tier-design-plan.md`](cold-tier-design-plan.md) adds two items
> to this extension's roadmap. **(R3 there)** an `arena_scan(LIST(VARCHAR))` overload — an explicit
> list of segment files, so the EOD roller's rolled-set == unlinked-set by construction (a `day :=`
> parameter would re-list the directory at bind time, a discover/roll/unlink TOCTOU). **(R7 there,
> v2)** a union replacement scan: `ArenaReplacement` returns a `SubqueryRef` holding
> `hist WHERE ts < cutover UNION ALL arena_scan WHERE ts >= cutover` (verified: DuckDB's binder
> wraps any returned `TableRef`, and pushdown flows through UNION ALL), with new settings
> `arena_hist_catalog` / `arena_hist_refresh_ms` — making bare `quotes` span realtime + Iceberg
> history in any DuckDB host.

## 10. Risks

1. **DuckDB C++ API instability** — the internal API moves between minor versions; the extension
   is pinned to v1.5.5 (same as the server's engine) and the template tracks upstream. Upgrades
   are deliberate events gated by the conformance suite. C-ABI migration noted for later.
2. **Vector-lifetime bugs** — chunks buffered by downstream operators outliving a scan's local
   state is the classic extension bug; per-vector mapping pins (§5) are the mitigation, and the
   parallel + ORDER BY tests exist to catch it.
3. **Type-mapping edges** — `TIME_NS` availability, tz-dropping on ns timestamps, small-decimal
   narrowing: all locked with explicit tests at D3, options documented in §5.
4. **`atomic_ref` on mapped memory** — technically UB-adjacent in strict reads of the standard but
   the established practice for shm (alignment guaranteed by the format); fallback is compiler
   builtins (`__atomic_load_n`), one function swap in `catalog.cpp`.
5. **Unsigned-extension friction** — every consumer needs the unsigned flag until signing is
   sorted; prominently documented. Version-mismatch load failures produce a clear DuckDB error
   (binary carries the pinned version).
6. **Polyglot repo** — a CMake/C++ toolchain lands in a Gradle repo; contained by keeping
   `arena-duckdb/` standalone (its own build, own CI job), which is also the own-repo escape
   hatch.
7. **Golden-corpus coupling** — the corpus is now load-bearing for two languages; any format
   change requires regenerating corpus + expected CSVs and re-running BOTH conformance suites
   before merge (add to the format-change checklist in `segment-format.md` at D6).
