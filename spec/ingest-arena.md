# Arena — schema-driven shared-memory columnar ingest

> Naming: the arena is the hot tier, implemented by the `:ascham-core` module. The metadata key
> prefix (`ascham.*`) and the segment magic (`ASCHAMFM`) are confirmed, not placeholders —
> changing either after a segment has been written is a format break.

## What this is

Build the **ingest and storage layer** for a real-time analytical store: a single-writer,
multi-reader, shared-memory columnar arena whose layout is derived entirely from an
**Apache Arrow schema**.

Producers append rows. Reader processes (in other languages) map the segment and query it
with zero copy. A downstream DuckDB-based query tier and a Parquet/Iceberg cold tier will
be built on top of this later — **they are not in scope here**, but the on-disk/in-memory
contract must be designed so they are straightforward to add.

The domain is financial market data (corporate bonds first, then equities, options, FX),
but **nothing in this module may be domain-specific**. It is an interpreter of schemas.

---

## Scope

### In scope

1. **Schema module** — load, validate, and canonicalise an Arrow schema plus arena metadata.
2. **Layout descriptor** — a deterministic, pure function from schema → byte layout.
3. **Segment format** — self-describing header, batch catalog, data region.
4. **Java writer** — segment lifecycle, the `Appender` row-writing contract, seal.
5. **Java reader** — snapshot semantics, zero-copy `VectorSchemaRoot` views over mapped memory.
6. **Conformance suite** — golden byte corpus + concurrency tests.

### Explicitly out of scope

- SBE → Arrow schema generation (handled externally; consume the Arrow schema only).
- The C++ reader and DuckDB table function.
- Arrow Flight / Flight SQL.
- Parquet or Iceberg writing.
- Reference-data content, resolution, or SCD-2 semantics. (The layout must *support* an
  instrument-code column pointing at a ref-data table by name; this module does not
  implement ref data.)
- Any Aeron or messaging dependency. This module must not depend on Aeron. Producers
  drive it; it does not subscribe to anything.

---

## Technology constraints

- **Java 21.** No preview features.
- **Agrona** for off-heap access (`UnsafeBuffer`, `MappedResizeableBuffer`) and for
  mapping >2 GB segments. Do not use `MappedByteBuffer` (2 GB cap).
- **arrow-java** (`arrow-vector`, `arrow-memory`, `arrow-c-data`) for schema types and for
  building reader-side views. **Never** use Arrow's allocators or `VectorSchemaRoot`
  construction on the write path.
- **Allocation-free hot path.** Append operations must allocate zero bytes steady-state.
  Prove this with a JMH `GC` profiler assertion in the benchmark suite.
- **VarHandle** with explicit `getAcquire` / `setRelease` for all cross-process ordering.
  No `synchronized`, no `volatile` fields as the primary mechanism, no `Unsafe` fences
  beyond what `VarHandle` provides.
- Segments live in `/dev/shm`. Assume Linux; do not add portability shims.

---

## Design: the Arrow schema is the source of truth

A table is defined by one Arrow schema. Everything else — buffer offsets, strides,
appender signatures, reader types — is derived from it.

### Supported type profile (v1)

Accept exactly these; **reject anything else at load time with a clear error**:

`Bool`, `Int8/16/32/64`, `UInt8/16/32/64`, `Float32`, `Float64`, `Decimal128(p,s)`,
`Date32`, `Time64(ns)`, `Timestamp(ns|us, tz)`, `FixedSizeBinary(n)`, `Utf8`, `Binary`.

Explicitly rejected in v1: `List`, `LargeList`, `Struct`, `Map`, `Union`, `Dictionary`,
`LargeUtf8`, `LargeBinary`, `Interval`, `Duration`, `Null`.

Two of those rejections are deliberate design decisions, not omissions — document them:

- **No nested types.** Hand-rolled layout for nested data is where correctness bugs live,
  and the reader must be implementable in C++ without an Arrow dependency.
- **No dictionary encoding.** Low-cardinality identifiers are stored as plain `Int32` codes
  and resolved against a separate reference-data table. Arrow's dictionary index is
  positional and stream-local, which breaks stable global identifiers, random access, and
  round-tripping to Parquet.

### Arena metadata in `custom_metadata`

Arrow schemas can't express capacity or pruning intent, so carry it in metadata. Keep it in
the schema rather than a sidecar file — one artifact, and generic Arrow tooling can still
read it.

**Schema-level:**

| Key | Meaning |
|---|---|
| `ascham.table` | table name |
| `ascham.schema_version` | integer, bumped on any change |
| `ascham.batch_rows` | target rows per sealed batch (default 65536) |
| `ascham.time_column` | column name used for time-range batch pruning |
| `ascham.stats_column` | integer column used for value-range batch pruning |

**Field-level:**

| Key | Meaning |
|---|---|
| `ascham.varlen_bytes` | byte capacity per batch for `Utf8`/`Binary` columns (required for those) |
| `ascham.sort_key` | integer ordinal, or absent |
| `ascham.family` | column family name, default `base` |
| `ascham.ref` | for `Int32` columns: name of the ref-data table this code resolves against |

Validation must be strict and total: a `Utf8` column without `ascham.varlen_bytes` is an
error, `ascham.time_column` must name a timestamp column, `ascham.stats_column` must name a
fixed-width integer column, and so on. Fail at load, never at append.

### Layout descriptor

A pure, deterministic function `(Arrow schema, arena metadata) → LayoutDescriptor`. Same
input must always produce byte-identical output; make this a property test.

Per column it yields: physical kind (fixed / varlen / bool-bitmap), validity buffer offset,
data buffer offset, element width, data capacity, offsets buffer offset (varlen only),
family id. **All offsets are relative to the batch base**, and every buffer base is
64-byte aligned.

---

## Design: segment layout

```
+--------------------+
| Header (fixed)     |  magic, versions, epoch, heartbeat, offsets
+--------------------+
| Arrow schema (IPC) |  serialised schema flatbuffer — makes the segment self-describing
+--------------------+
| Layout descriptor  |  derived, but written out so readers need no build-time coupling
+--------------------+
| Batch catalog      |  fixed-size, 64-byte-padded entries
+--------------------+
| Data region        |  batch regions at capacity stride, append-only, never rewound
+--------------------+
```

**Header** carries: magic `ASCHAMFM`, format version, header length, SHA-256 of the
canonical schema bytes, writer epoch (`int64`), heartbeat counter (`int64`, on its own
cache line), segment sequence number, arena capacity, and the offset/length of each region.

**Catalog entry** (padded to 64 bytes, one per cache line):

- `length` (`int64`) — **bit 63 set means the batch is still accumulating.** Row count is
  `length & Long.MAX_VALUE`. Do not use a negative sentinel: `-0 == 0`, so a zero-row
  in-progress batch would be indistinguishable from a sealed empty one.
- `base_offset` (`uint64`), segment-relative.
- `ts_min`, `ts_max` — from `ascham.time_column`.
- `stat_min`, `stat_max` — from `ascham.stats_column`.
- `seal_nanos`.

**Two regions, one mechanism.** Sealed batches and the in-progress batch live in the same
data region and use the same layout. Sealing is *not* a copy: rows are accumulated directly
in their final arena location at capacity stride, and sealing is a single release-store
that clears bit 63 and appends the next catalog entry. Wasted space per batch is bounded by
(capacity − rows), which is negligible when sealing on row count.

**Seal on row count, not on a timer.** Freshness comes from readers seeing the in-progress
batch, so sealed batches carry no visibility obligation. Target 32k–128k rows.

---

## Invariants — do not violate these

These are the correctness core. Each needs a comment in the code pointing at this section,
and none may be changed without an explicit decision.

1. **The writer never rewinds.** Rows below a published count are immutable for the life of
   the segment. This is what makes reader pointers valid indefinitely and removes any need
   for epoch reclamation or reader registration.
2. **Publication order.** Write data buffers → for varlen, write `offsets[n+1]` → *then*
   release-store the catalog `length`. A reader observing `n` rows must find `offsets[n]`
   already published; varlen needs `n+1` offsets for `n` rows.
3. **Validity bitmaps** are a byte-level read-modify-write touching a byte that already
   contains published bits. Safe because the writer only ever *sets* the new bit and no one
   else writes that byte — readers see old-or-new, both correct below `n`.
4. **Never store pointers, only segment-relative offsets.** Every reader maps at a
   different base address.
5. **Every buffer base is 64-byte aligned.** Arrow and DuckDB readers are entitled to
   assume this; violating it silently degrades to a copy or faults.
6. **Capacity must not end flush against an unmapped page.** Readers overread in 64-byte
   chunks past the row count; the values are masked off by length, but the bytes must be
   mapped.
7. **A schema hash mismatch is a hard failure at open.** A reader misinterpreting a layout
   produces plausible garbage, not a crash — the worst possible failure mode.
8. **Column families have independent watermarks** and are joined *positionally* by row
   index. Row count exposed to a reader is `min(watermarks)` across families. (Model family
   id in the descriptor from day one; v1 writer may support only the `base` family.)

---

## API surface

### Writer

One row-writing contract — the `Appender` interface: `beginRow()`, ordinal-addressed setters
(`setLong(col, v)`, `setBytes(col, buf, off, len)`, …, taking primitives and `DirectBuffer`
slices, never `String` or boxed types), `endRow()`. Allocation-free steady-state. Two
implementations: the descriptor-driven `GenericAppender` bound to a single segment, and the
`RollingAppender` that spans segment rotations (rotation decided exception-free inside
`beginRow`/`setBytes`, including mid-row open-row adoption into the successor segment).
(An earlier codegen'd typed appender — named per-column setters generated from the schema —
was built and later removed: no consumers, and the generic path already met the
allocation-free requirement.)

Plus: `createSegment(schema, capacity, epoch)`, `seal()`, `rotate()`, `close()`,
`heartbeat()`.

### Reader

- `Snapshot open()` — acquire-load catalog count and every entry length **once**, resolve
  all row counts, and freeze. A snapshot must never re-read a catalog entry; a stale
  snapshot is always safe, an inconsistent one is not.
- `List<BatchView> batches()` — zero-copy `VectorSchemaRoot` views built by wrapping arena
  regions as `ArrowBuf` with `ReferenceManager.NO_OP` and calling `loadFieldBuffers`.
- `prune(tsRange, statRange)` — filter the batch list on catalog min/max.
- Liveness: expose writer epoch and heartbeat staleness so a caller can distinguish a quiet
  writer from a dead one. A stuck in-progress batch must be detectable.

---

## Milestones

**M0 — spec.** Write `docs/segment-format.md` with the exact byte layout, the type profile,
the metadata keys, and the invariants above. **Stop and get review before writing
implementation code.**

**M1 — schema + descriptor.** Loader, validator, canonical serialisation, descriptor
computation. Pure functions, no I/O. Property test: descriptor generation is deterministic
and idempotent.

**M2 — writer.** Segment creation, generic appender, seal, catalog maintenance.

**M3 — reader.** Snapshot, batch views, pruning. This validates the layout end-to-end
without needing the C++ reader to exist.

**M4 — conformance and concurrency.** See below.

**M5 — rotation and liveness.** Segment rotation with retention; `shm_unlink` on eviction
(the kernel refcount keeps memory alive for readers that still have it mapped, which is
exactly the reclamation semantics we want). Epoch/heartbeat orphan detection.

---

## Testing

- **Golden corpus** — a manifest of schemas × generated data, with expected segment bytes
  checked into the repo. This is the cross-language contract; the future C++ reader will be
  validated against exactly these files. Cover every supported type, plus: empty batch,
  all-null column, all-non-null column, varlen at exact byte capacity, varlen empty strings,
  in-progress batch mid-append, `FixedSizeBinary` at several widths, min/max at type bounds.
- **jcstress** for the ordering invariants — particularly the varlen `offsets[n+1]`-before-
  `length` rule and the validity-byte RMW. These are exactly the bugs that survive normal
  testing and appear in production.
- **Concurrency soak** — one writer, N readers, hours, asserting: row counts are monotonic,
  no torn values, every varlen value read is well-formed, and a snapshot taken at time T
  still reads identically at T+long.
- **JMH** — append throughput per type, seal latency, snapshot construction cost as a
  function of batch count. Assert zero steady-state allocation via the GC profiler.

---

## Raise before implementing

Ask rather than assume:

1. Should `Decimal128` be in v1, or is `Int64` with an implied scale sufficient for prices?
2. Are unsigned types actually needed, given DuckDB and Parquet handle them unevenly?
3. Timestamp precision — always nanoseconds, or per-column?
4. Should the varlen byte capacity trigger a seal independently of the row count (whichever
   binds first)? Assume yes unless told otherwise.
5. Is one designated stats column enough for v1 pruning, or is a variable-length catalog
   entry needed now?
6. Segment sizing policy: one per day, or time-sliced rotation from the start?