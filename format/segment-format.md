# The ascham segment format

The on-disk / in-shared-memory byte contract, and the internals reference for anyone implementing
against it. This document plus [`Layout.fbs`](Layout.fbs) in this directory are jointly
**authoritative**: no offset, field, or ordering rule here may change without a format-version bump.

Read this if you need to understand how a segment is laid out, how the single-writer/many-reader
protocol works, how to add a language binding, or how to change the format. If you only want to
*use* an existing implementation, read [`../docs/java-guide.md`](../docs/java-guide.md) or
[`../docs/cpp-guide.md`](../docs/cpp-guide.md) instead.

Both reference implementations live in this repo — Java under `ascham-core/`, C++ under `cpp/` — and
both are validated against the golden corpus in `conformance/` on every `./gradlew check`, so a
format change is checked against both languages before it leaves the repo.

## Confirmed identifiers

| Identifier | Value | Notes |
|---|---|---|
| Segment magic | `ASCHAMFM` (8 ASCII bytes) | Changing after any segment is written is a hard format break. |
| Format version | `2` | `SegmentFormat.FORMAT_VERSION` (Java), `arena::fmt` (C++). |
| Metadata key prefix | `ascham.*` | Carried in the Arrow schema `custom_metadata`, so it is hashed into every header. |
| Layout file identifier | `ALD2` | Flatbuffers `file_identifier` of the layout-descriptor region. |
| Java base package | `io.ascham` | Not part of the byte contract, but pinned alongside the above. |

## Conventions

- **Endianness:** little-endian for every multi-byte integer and float, everywhere (header, catalog,
  layout descriptor, and column data buffers). This matches x86-64 / aarch64 and Arrow's native
  layout, so reader-side buffers are consumed with zero byte-swapping.
- **Offsets:** every offset written into the header region table and every catalog `base_offset` is
  **segment-relative** — measured from byte 0 of the mapping. Offsets inside a batch (in the layout
  descriptor) are **batch-relative** — measured from that batch's base. Never a pointer (invariant 4:
  every reader maps at a different base address).
- **Alignment:** every column buffer base is 64-byte aligned (invariant 5); every batch base is
  page-aligned (4096). See [Alignment](#alignment--overread).
- **Sizes:** `int32`/`int64` are two's-complement; `uint64` where noted is an unsigned magnitude that
  never exceeds `Long.MAX_VALUE` in v1 (segments are far smaller than 8 EiB), so it is safe to carry
  in a Java `long`.

## Segment structure

A segment is a single file in `/dev/shm`, laid out as five contiguous, non-overlapping regions:

```
offset 0
+----------------------------------+
| Header            (4096 bytes)   |  fixed size; identity, liveness, region table
+----------------------------------+
| Arrow schema (IPC message bytes) |  64-aligned start; the canonical, self-describing schema
+----------------------------------+
| Layout descriptor (Layout.fbs)   |  64-aligned start; derived, written out for coupling-free readers
+----------------------------------+
| Batch catalog     (N * 64 bytes) |  64-aligned start; one 64-byte (one-cache-line) entry per batch
+----------------------------------+
| Data region       (N * stride)   |  page-aligned start; batch regions at capacity stride, append-only
+----------------------------------+
= arena_capacity (total file size)
```

The **family-watermarks region** (invariant 8) is reserved in v1: its region-table slot is present
but its offset and length are both 0. A future format version places it between the catalog and data
regions and populates one length-array per column family.

### Create-time sizing

Region placement is computed once, at `createSegment`, from the canonical schema bytes, the layout
descriptor bytes, and a requested batch capacity `N` (the maximum number of batches the segment can
hold before it must rotate):

```
header_off   = 0                                   ; header_len = 4096
schema_off   = 4096                                ; schema_len = |canonical Arrow IPC schema bytes|
layout_off   = align64(schema_off + schema_len)    ; layout_len = |Layout.fbs flatbuffer bytes|
catalog_off  = align64(layout_off + layout_len)    ; catalog_len = N * 64
data_off     = alignPage(catalog_off + catalog_len); data_len   = N * batch_stride
arena_capacity = data_off + data_len               ; = total file size
```

`N = catalog_len / 64` is therefore recoverable by a reader from the region table alone. The writer
rotates (starts a new segment) before it would open batch `N`. One rotation case starts mid-row:
when a varlen column exhausts its per-batch bytes while batch `N-1` is open, the writer adopts the
partially-written open row into batch 0 of the successor segment (the cross-segment arm of the
varlen-exhaustion migration). The old and new segments briefly coexist mapped in the writer; the
open row was never counted in any published `length`, so readers of the old segment see only its
completed prefix, and the row becomes visible in the new segment through the ordinary append
release-store.

### Create-time atomicity

A reader must never observe a half-initialised header. The writer creates the file at a temporary
path, writes the header, schema, and layout-descriptor regions, `ftruncate`s to `arena_capacity`,
then atomically `rename(2)`s it to its final name. The header's static identity fields (magic,
version, hashes, region table, epoch) are therefore fully written before the segment is visible; no
acquire/release is needed for them.

## Header (4096 bytes)

Fields are grouped by cache line (64 bytes). The **heartbeat** and **active_batch_count** each occupy
their own cache line to avoid false sharing (the writer updates them while readers poll them). All
remaining bytes in each line are reserved and MUST be written as zero.

| Offset | Size | Type | Field | Written | Ordering |
|---|---|---|---|---|---|
| 0   | 8  | 8×ASCII | `magic` = `ASCHAMFM` | at create | plain |
| 8   | 4  | int32 | `format_version` = 2 | at create | plain |
| 12  | 4  | int32 | `header_length` = 4096 | at create | plain |
| 16  | 32 | bytes | `schema_sha256` — SHA-256 of the canonical schema bytes | at create | plain |
| 48  | 8  | int64 | `segment_sequence` — monotonic per (table, rotation) | at create | plain |
| 56  | 8  | int64 | `arena_capacity` — total file size in bytes | at create | plain |
| 64  | 8  | int64 | `writer_epoch` — identifies the writing process instance | at create | plain |
| 72  | 8  | int64 | `batch_rows` — row capacity of each batch (`ascham.batch_rows`) | at create | plain |
| 80  | 8  | int64 | `batch_stride` — bytes reserved per batch | at create | plain |
| 88  | 40 | — | reserved (zero) | | |
| 128 | 8  | int64 | `heartbeat` — liveness counter (own cache line) | steady state | **release** / acquire |
| 136 | 56 | — | reserved (zero) | | |
| 192 | 8  | int64 | `active_batch_count` — number of catalog entries opened (own cache line) | per batch open | **release** / acquire |
| 200 | 56 | — | reserved (zero) | | |
| 256 | 8  | int64 | `schema_region_offset` | at create | plain |
| 264 | 8  | int64 | `schema_region_length` | at create | plain |
| 272 | 8  | int64 | `layout_region_offset` | at create | plain |
| 280 | 8  | int64 | `layout_region_length` | at create | plain |
| 288 | 8  | int64 | `catalog_region_offset` | at create | plain |
| 296 | 8  | int64 | `catalog_region_length` | at create | plain |
| 304 | 8  | int64 | `data_region_offset` | at create | plain |
| 312 | 8  | int64 | `data_region_length` | at create | plain |
| 320 | 8  | int64 | `family_watermarks_region_offset` (reserved, 0 in v1) | at create | plain |
| 328 | 8  | int64 | `family_watermarks_region_length` (reserved, 0 in v1) | at create | plain |
| 336 | 3760 | — | reserved (zero) | | |

The region table at offset 256 is five 16-byte `(offset, length)` entries in the fixed order
schema, layout, catalog, data, family-watermarks.

`schema_sha256` is re-verified at open against a SHA-256 computed over the embedded schema region; a
mismatch is a hard failure (invariant 7).

## Arrow schema region

The canonical Arrow IPC **schema message** bytes (`Schema.serializeAsMessage()` in Java), with all
`custom_metadata` — schema-level and field-level — emitted in sorted key order so the bytes, and
therefore `schema_sha256`, are insensitive to metadata insertion order. This makes the segment
self-describing: a reader parses the schema with a standard Arrow IPC reader and needs no build-time
coupling to the writer. `schema_sha256` in the header is the SHA-256 of exactly these bytes.

The schema carries what the layout descriptor deliberately omits: logical types (decimal
precision/scale, timestamp unit and timezone) and the `ascham.*` metadata. A reader that only needs
to address bytes can ignore it; a reader that needs to interpret values cannot.

## Layout descriptor region (`Layout.fbs`)

The layout descriptor is *derived* from the schema, but written out so a reader (including one with
no Arrow dependency) can consume the byte layout directly without re-deriving it.

Since format version 2, the region holds exactly one **Flatbuffers buffer** whose wire structure is
defined by [`Layout.fbs`](Layout.fbs) — the IDL is authoritative for this region, the same split
Arrow itself uses (IDL for the metadata envelope, prose for the physical layout). The root table is
`io.ascham.flatbuf.LayoutDescriptor`, file identifier `ALD2`; the region length in the header's
region table is the buffer length, and the region's 64-aligned base satisfies flatbuffers alignment.

A reader may cross-check the descriptor against the embedded schema, but **the descriptor is
authoritative for byte offsets**. (Format version 1 used a bespoke little-endian sequential codec;
v1 segments are no longer readable.)

See [FlatBuffers in this format](#flatbuffers-in-this-format) for the canonical-encoding rules and
where the generated bindings live.

### Trusting the buffer

A reader consuming a segment another process wrote over shared memory is reading untrusted bytes: it
must run the generated flatbuffers **verifier** before touching any accessor, or a malformed
descriptor becomes out-of-bounds reads.

The two reference implementations differ here, deliberately:

- **C++ verifies.** `LayoutDescriptor::decode` (`cpp/src/format/layout.cpp`) calls
  `io_ascham_flatbuf_LayoutDescriptor_verify_as_root_with_identifier` before any accessor and throws
  `FormatError` on rejection. The C++ reader is the one that maps files it did not write — hostile
  input is in its threat model.
- **Java does not.** `LayoutCodec.decode` checks only the `ALD2` file identifier and the region
  length. The Java reader is used against segments written by the Java writer in the same
  deployment; the verifier would be checking the process's own output.

Any new binding intended to read segments from other writers should follow the C++ side.

## Batch catalog

A fixed array of `N = catalog_region_length / 64` entries, each exactly 64 bytes (one cache line, so
the single in-progress entry is naturally isolated from sealed ones). Entry `k` describes batch `k`.
Entries `[0, active_batch_count)` are live; the rest are zero.

| Offset | Size | Type | Field |
|---|---|---|---|
| 0  | 8 | int64  | `length` — see below |
| 8  | 8 | uint64 | `base_offset` — segment-relative offset of batch `k`'s data (`= data_region_offset + k * batch_stride`) |
| 16 | 8 | int64  | `ts_min` — min of `ascham.time_column` over the batch |
| 24 | 8 | int64  | `ts_max` — max of `ascham.time_column` |
| 32 | 8 | int64  | `stat_min` — min of `ascham.stats_column` (0 if no stats column) |
| 40 | 8 | int64  | `stat_max` — max of `ascham.stats_column` (0 if no stats column) |
| 48 | 8 | int64  | `seal_nanos` — wall-clock nanos at seal; 0 while in progress |
| 56 | 8 | — | reserved (zero) |

**`length` and the in-progress bit.** Bit 63 set means the batch is still accumulating. The row count
is `length & Long.MAX_VALUE`. A negative sentinel is deliberately **not** used: `-0 == 0`, so a
zero-row in-progress batch would be indistinguishable from a sealed empty one.

- In progress: `length = row_count | (1 << 63)`.
- Sealed: `length = row_count` (bit 63 clear).

`length` is the segment's sole publication point. Only `length` is written with release / read with
acquire; every other field in the entry is plain (see [Concurrency](#concurrency-contract)).

## Data region

Batch `k` occupies `[base_offset, base_offset + batch_stride)`. Rows accumulate **in place** at
capacity stride — sealing is not a copy (invariant 1: the writer never rewinds; rows below a
published count are immutable for the life of the segment). Wasted tail space per batch is
`batch_stride - used`, negligible when sealing on row count.

Because sealing is not a copy, an explicit `seal()` opens the next batch eagerly. A segment therefore
normally carries a trailing **empty in-progress batch** (row count 0), and a reader's batch count
includes it. This is expected: readers tolerate empty batches, and an in-progress batch is never
pruned. Batch 0 is opened at segment creation.

Within a batch, each column's buffers sit at the batch-relative offsets from the layout descriptor.
Buffer contents follow the Arrow memory format exactly, so reader-side `ArrowBuf` wrapping is
zero-copy:

- **Validity bitmap** (all kinds): 1 bit per row, **LSB-first** within each byte (Arrow convention).
  A set bit means the value is valid (non-null). Ceil(`batch_rows`/8) bytes.
- **FIXED data:** `element_width` bytes per row, little-endian. Row `i` at `data_offset + i *
  element_width`.
  - `Decimal128`: 16 bytes, little-endian two's-complement, scale from the schema.
  - `FixedSizeBinary(n)`: `n` raw bytes.
  - `Date32`: int32 days since epoch. `Time64(ns)`: int64 nanoseconds. `Timestamp(ns|us)`: int64 in
    the schema-declared unit.
- **BOOL_BITMAP data:** 1 bit per row, LSB-first; set bit = true. (A `Bool` column has a validity
  bitmap *and* a separate data bitmap.)
- **VARLEN** (`Utf8`/`Binary`): an int32 **offsets** buffer of `batch_rows + 1` entries plus a byte
  **data** buffer. `offsets[0] = 0`; value `i` occupies data bytes `[offsets[i], offsets[i+1])`.
  `n` rows require `n + 1` offsets. A null or empty value has `offsets[i+1] == offsets[i]`.

### Alignment & overread

Every buffer base is 64-byte aligned (invariant 5): Arrow and DuckDB readers are entitled to assume
this, and violating it silently degrades to a copy or faults. Each batch's stride is padded up to a
4096-byte page so the batch never ends flush against an unmapped page (invariant 6): readers overread
in 64-byte chunks past the row count and mask the excess off by length, but the bytes must be mapped.
The page-padded stride guarantees that tail.

## Concurrency contract

Single writer, many readers, across processes. Coordination is entirely through **`length`** (and the
liveness fields), using acquire/release on `length`; all data, offsets, and non-`length` catalog
fields are plain stores made visible transitively.

### Writer publication protocol (invariant 2)

Opening batch `k`:
1. plain-write catalog entry `k`: `base_offset`, zeroed stats, `seal_nanos = 0`;
2. **release-store** `length[k] = 0 | (1 << 63)` (in-progress, zero rows);
3. **release-store** `active_batch_count = k + 1`.

Because the entry's `base_offset` is written before the release in step 2, and `active_batch_count`
is released after (step 3), a reader that acquires `active_batch_count` and then acquires `length[k]`
is guaranteed to see a published `base_offset`.

Appending row `n` (0-based) to batch `k` — this is the only ordering that matters, and it lives in one
method (`BatchCursor.endRow`) so it cannot be reordered by mistake:
1. write the row's fixed cells / copy its varlen bytes into the batch (plain);
2. for **every** VARLEN column, write `offsets[n+1]` (plain) — a reader observing `n` rows must find
   `offsets[n]` already published, and `n` rows need `n+1` offsets;
3. set the row's validity bit — a byte-level read-modify-write that only ever *sets* the new bit in a
   byte no other thread writes, so readers see old-or-new, both correct below `n` (invariant 3);
4. **release-store** `length[k] = (n + 1) | (1 << 63)`.

Sealing batch `k`:
1. plain-write final `ts_min`/`ts_max`/`stat_min`/`stat_max` and `seal_nanos`;
2. **release-store** `length[k] = row_count` (bit 63 cleared).

The stats and `seal_nanos` in step 1 become meaningful to a reader only once it observes bit 63
cleared in step 2 — which is exactly why in-progress batches are excluded from pruning (below).

### Reader snapshot protocol

A snapshot freezes a consistent view and never re-reads (a stale snapshot is always safe; an
inconsistent one is not):
1. **acquire-load** `active_batch_count` → `C`, once;
2. for each `k` in `[0, C)`, **acquire-load** `length[k]` once, recording `row_count = length &
   Long.MAX_VALUE` and `sealed = (length >= 0)`;
3. freeze. Every subsequent read uses the captured counts. Batch `k`'s valid rows are `[0,
   row_count)`; all their buffers and `offsets[0..row_count]` are guaranteed published.

Reader pointers stay valid indefinitely because the writer never rewinds (invariant 1); no epoch
reclamation or reader registration is required.

### Pruning

`prune(tsRange, statRange)` filters **sealed** batches on `[ts_min, ts_max]` / `[stat_min, stat_max]`
overlap. **In-progress batches are never pruned** — their catalog stats are unpublished until the
bit-63-clearing seal, so they are always included in the result and read live.

### Column families (invariant 8)

Families have independent watermarks and are joined positionally by row index; the row count exposed
to a reader is `min(watermarks)` across families. v1 stores only the `base` family, so `length[k]` is
that watermark directly and the family-watermarks region is reserved/empty. The layout descriptor
already carries `family_id` per column and the family-name table, so the model is present from day
one; the v1 writer rejects schemas with more than one family at create (never at append).

### Liveness

Readers distinguish a quiet writer from a dead one via `writer_epoch` (constant per writer instance;
bumped when a writer restarts) and `heartbeat` (a counter the writer advances periodically,
release-stored). A stuck in-progress batch is detectable as `length[k]` unchanged while `heartbeat`
advances.

### Implementing the contract

Obligations a binding author has to honour that do not fall out of the byte tables.

**Single writer *per table*, many independent readers, across processes via `mmap`.** Two tables mean
two writers; one table means one thread. There are no locks, no atomics beyond the ordered fields
below, and no `synchronized`/`volatile` anywhere in the reference Java implementation.

**The ordered fields are exhaustively these three:** catalog `length`, header `heartbeat`, header
`active_batch_count`. Nothing else is ordered. Every other write — data buffers, varlen offsets,
validity bits, `base_offset`, the stats, `seal_nanos` — is a plain store made visible transitively by
the release of `length`.

**Confine ordered access to one place.** Both implementations funnel it through a single type, on
purpose, so the memory-model reasoning lives in one file that can be reviewed as a unit:

- Java: `io.ascham.segment.ControlRegion`, which owns one
  `MethodHandles.byteBufferViewVarHandle(long[].class, LITTLE_ENDIAN)` and exposes
  `getLongAcquire`/`putLongRelease`. It wraps a `MappedByteBuffer` over `[0, data_offset)` only.
- C++: `arena::acquire_i64` in `arena_format.hpp`, which is
  `std::atomic_ref<int64_t>::load(memory_order_acquire)` with an `__atomic_load_n` fallback.

The Java split — a small `MappedByteBuffer` control region for ordered access, an Agrona
`UnsafeBuffer` over the data region for plain and bulk access — exists because VarHandles need a
`ByteBuffer` coordinate and cannot address raw mapped memory. It costs nothing, because publication
funnels through `length` and so the data region needs no ordered access at all. It also confines the
`sun.misc.Unsafe`-adjacent code to one class, which matters given JEP 471/498.

**Ordered fields are 8-byte aligned by format construction, and you must not assume that of the
file.** Java throws at runtime on a misaligned `VarHandle` access, so `ControlRegion`'s constructor
probes alignment; the C++ reader validates `catalog_offset % 8` *before* its first acquire load
through that pointer, because a hostile file can claim any offset.

**Two writes are plain where they look like they should be atomic**, and are correct only under the
single-writer rule:

- `heartbeat` is bumped with a plain read followed by a release store, not an atomic increment.
- The validity bit is set with a plain non-atomic byte OR (invariant 3). It is safe because the
  writer only ever *sets* bits, and no other thread writes that byte — so a racing reader sees the
  old or new byte, and both are correct for rows below the published count.

If you port the writer to a language where you are tempted to make these atomic, the right response
is to check that you have not broken the single-writer assumption.

**Rotation and reclamation.** Rotating closes only the writer's mapping of the old segment; readers
hold independent mappings and are unaffected, and the file stays on disk until something unlinks it.
During a mid-row rotation the old and new segments are briefly mapped simultaneously — a transient
shared-memory spike of one segment's size. Eviction is `unlink(2)` (`shm_unlink` semantics): the
kernel refcount keeps the inode alive for readers that still have it mapped, which is exactly the
reclamation behaviour the format wants, and is why there is no epoch reclamation or reader
registration.

**The 2 GB segment cap is a Java limitation, not a format rule.** `SegmentFile` rejects a requested
capacity above `Integer.MAX_VALUE`, because Agrona 2.x removed `MappedResizeableBuffer` and
`UnsafeBuffer` caps at an `int` capacity; on Java 21 the alternatives were preview APIs or reflection
into `FileChannelImpl`. A logical table spans multiple ≤2 GB segments via capacity-triggered
rotation, which the design provides anyway. Nothing in the byte format encodes this limit — do not
reproduce it in a new binding.

**How the invariants are pinned.** The ordering rules above are exercised directly by the jcstress
harness in `ascham-core/src/jcstress` (`OffsetsBeforeLengthTest` for invariant 2,
`ValidityByteRmwTest` for invariant 3, `RowCountMonotonicTest` for invariant 1, `SealBit63Test` for
the seal publication order), and end to end by `SoakTest` (one writer, N readers, asserting no torn
reads, monotonic row counts, and frozen-snapshot stability).

## Supported type profile (v1)

Accept exactly these; reject anything else at **load** with a clear error (never fail at append):

`Bool`, `Int8/16/32/64`, `UInt8/16/32/64`, `Float32`, `Float64`, `Decimal128(p,s)`, `Date32`,
`Time64(ns)`, `Timestamp(ns|us, tz)`, `FixedSizeBinary(n)`, `Utf8`, `Binary`.

Rejected in v1: `List`, `LargeList`, `Struct`, `Map`, `Union`, `Dictionary`, `LargeUtf8`,
`LargeBinary`, `Interval`, `Duration`, `Null`, and out-of-profile variants (`Float16`, `Decimal256`,
`Date64`, `Time32`, non-ns/us `Timestamp`).

Two rejections are deliberate design decisions, not omissions:

- **No nested types.** Hand-rolled layout for nested data is where correctness bugs live, and the
  reader must be implementable in C++ without an Arrow dependency.
- **No dictionary encoding.** Low-cardinality identifiers are stored as plain `Int32` codes and
  resolved against a separate reference-data table (via `ascham.ref`). Arrow's dictionary index is
  positional and stream-local, which breaks stable global identifiers, random access, and
  round-tripping to Parquet.

The accept/reject matrix is pinned language-neutrally by `conformance/type_profile_vectors.json`.

## Metadata keys

Carried in the Arrow schema `custom_metadata` (schema level) and each field's metadata (field level),
rather than a sidecar file — one artifact, and generic Arrow tooling can still read it. Validation is
strict and total: any unknown `ascham.*` key is an error. Keys outside the `ascham.` prefix are
ignored and pass through untouched (they are still hashed into `schema_sha256`, since they are part
of the canonical schema bytes).

**Schema-level:**

| Key | Meaning |
|---|---|
| `ascham.table` | table name (required) |
| `ascham.schema_version` | integer, bumped on any change (required) |
| `ascham.batch_rows` | target rows per sealed batch (default 65536) |
| `ascham.time_column` | timestamp column for time-range pruning (required; drives `ts_min`/`ts_max`) |
| `ascham.stats_column` | fixed-width integer column for value-range pruning (optional; drives `stat_min`/`stat_max`) |

**Field-level:**

| Key | Meaning |
|---|---|
| `ascham.varlen_bytes` | byte capacity per batch for `Utf8`/`Binary` columns (required for those, forbidden on others) |
| `ascham.sort_key` | integer ordinal, unique across columns, or absent |
| `ascham.family` | column-family name, default `base` |
| `ascham.ref` | for signed `Int32` columns: name of the ref-data table this code resolves against |

## Invariants (correctness core)

The correctness core. Each has format-level consequences pinned above and, in code, a comment
pointing here.

1. **The writer never rewinds.** Rows below a published count are immutable for the segment's life —
   what makes reader pointers valid indefinitely (no epoch reclamation, no reader registration).
2. **Publication order.** Data buffers → varlen `offsets[n+1]` → release-store catalog `length`. A
   reader observing `n` rows finds `offsets[n]` published; varlen needs `n+1` offsets for `n` rows.
3. **Validity bitmaps** are a byte-level RMW touching a byte that already contains published bits;
   the writer only *sets* the new bit and no one else writes that byte, so readers see old-or-new,
   both correct below `n`.
4. **Never store pointers, only segment-relative offsets.** Every reader maps at a different base.
5. **Every buffer base is 64-byte aligned.** Arrow/DuckDB readers assume it; violating it silently
   degrades to a copy or faults.
6. **Capacity must not end flush against an unmapped page.** Readers overread in 64-byte chunks past
   the row count; the values are masked off by length, but the bytes must be mapped (page-padded
   stride).
7. **A schema hash mismatch is a hard failure at open.** A reader misinterpreting a layout produces
   plausible garbage, not a crash — the worst possible failure mode.
8. **Column families have independent watermarks**, joined positionally by row index; exposed row
   count is `min(watermarks)`. Modeled in the descriptor from day one; v1 writer supports only `base`.

## FlatBuffers in this format

Only the layout-descriptor region is FlatBuffers. Everything else — the header, the catalog, and the
data-region byte layout — is prose in this document, hand-implemented per language. That split is
copied from Apache Arrow, which defines its metadata envelope in `Message.fbs` and its physical
buffer layout in prose, and it is deliberate: the layout descriptor is the one message-shaped,
evolving part of the format, while the header and catalog are fixed-offset structures whose whole
point is that a reader can address them with two loads and no parsing.

**Explicit enum values are contract.** `Layout.fbs` writes out `Fixed = 0`, `Varlen = 1`,
`BoolBitmap = 2`. Never reorder, never renumber. Each language mirrors these in a hand-written domain
enum (`io.ascham.layout.PhysicalKind`, `arena::PhysicalKind`) and a test on each side asserts the
wire values still agree with the IDL — `PhysicalKindWireTest` in Java,
`cpp/test/test_layout_vectors.cpp` in C++.

**The encoding is canonical, and the format depends on it.** Identical descriptors must serialize to
identical bytes, because the golden corpus compares whole segments byte-for-byte. The writer
therefore:

- builds with `FlatBufferBuilder.forceDefaults(true)`, so a field equal to its default is still
  written out and the buffer does not change shape with the data;
- writes table fields through the generated `create*` helpers, which emit fields in declaration
  order;
- builds vectors in ordinal order.

This is pinned by `conformance/layout_vectors.jsonl` (520 schema → descriptor-bytes vectors) and by
the Java layout-determinism property test. A binding that only *reads* descriptors does not need to
reproduce this; a binding that writes them does.

**Bindings are generated at development time and checked in** — there is no build-time code
generation, following the arrow-java `arrow-format` pattern. The generated sources are ordinary
checked-in files:

| Language | Generator | Output |
|---|---|---|
| Java | `flatc` 25.2.10 | `ascham-core/src/generated/java/io/ascham/flatbuf/` |
| C | `flatcc` v0.6.2, reader + verifier only | `cpp/src/format/layout_generated.h` |

Never hand-edit either. Regenerate with the script below.

## Regenerating the bindings

One script regenerates **both** languages from `format/Layout.fbs`:

```sh
dev/update_flatbuffers.sh
```

**Java (`flatc`).** Requires `flatc` on PATH at exactly **25.2.10**; the script checks and hard-fails
otherwise. The pin must match the `com.google.flatbuffers:flatbuffers-java` runtime in
`ascham-core/build.gradle.kts` — which is also the version Arrow Java 19.0.0 ships against, so the
three cannot drift independently. The script wipes and regenerates
`ascham-core/src/generated/java/io/ascham/flatbuf/`. Release binaries:
<https://github.com/google/flatbuffers/releases/tag/v25.2.10>.

**C (`flatcc`).** Hermetic — nothing needs to be on PATH. The script clones and builds the flatcc
compiler at **v0.6.2** in a temp directory, runs it, and prepends a "GENERATED … DO NOT EDIT" banner
to `cpp/src/format/layout_generated.h`. The pin matches the flatcc **runtime** vendored via the
nanoarrow amalgamation (`cpp/src/vendor/nanoarrow/src/flatcc.c`); generated code and runtime must
agree. Flags are `--common --reader --verifier --recursive` — `--common` inlines
`flatbuffers_common_reader.h`, which is not vendored standalone, and there is no `--builder` because
readers never write descriptors.

**The general rule for any third language:** the generator version must match the runtime version
you link against. Pin both, assert the pin in the script, and say in a comment where the runtime
version comes from — that comment is what stops the next person breaking it.

## Adding a language binding

Generating the flatbuffers bindings is the small part. Most of the format is prose, so most of a
binding is hand-written. In order:

1. **Generate the reader bindings** for `Layout.fbs` in your language and add the invocation to
   `dev/update_flatbuffers.sh` next to the existing two, with a version pin and a comment saying
   what the pin is tied to.

2. **Hand-implement the fixed-offset parts**: header decode, region table, batch catalog, and
   data-region addressing, from the tables in this document. Both existing mirrors are small and
   worth reading side by side — `io.ascham.segment.SegmentFormat` (Java) and
   `cpp/src/format/arena_format.hpp` (C++, which also carries the `load_le<T>`, `acquire_i64`,
   `row_count_of` and `is_in_progress` helpers).

3. **Implement the reader snapshot protocol exactly** as specified below: acquire-load
   `active_batch_count` once, then each entry's `length` once, then freeze. Re-reading is the bug
   this protocol exists to prevent.

4. **Mirror the `PhysicalKind` wire values** in a native enum and add a test asserting they match the
   IDL, as both existing implementations do.

5. **Decide your trust boundary.** If your reader will map segments other processes wrote, run the
   flatbuffers verifier and bounds-check every offset you take out of the descriptor. See
   [Trusting the buffer](#trusting-the-buffer) and the hardening in `cpp/src/format/segment_reader.cpp`
   (`require_in_bounds` in its overflow-safe form, `validate_layout`, varlen offset validation, and
   the `catalog_offset % 8` check that runs *before* the first acquire load).

6. **Validate against `conformance/`.** This is the acceptance test for a new binding, and it is
   deliberately language-neutral:

   | Fixture | What it pins |
   |---|---|
   | `golden/*.bin` + `manifest.json` | 8 whole segments, with SHA-256s — the byte contract |
   | `schemas/*.arrows` + `schema_hashes.json` | canonical schema bytes and their hashes |
   | `expected/*.csv` | decoded row values per case |
   | `layout_vectors.jsonl` | 520 schema → descriptor-byte vectors |
   | `type_profile_vectors.json` | accepted/rejected types — hand-authored, so neither existing implementation is the authority |

   The corpus covers every supported type plus empty batch, all-null, varlen at exact capacity,
   varlen migration, varlen empty strings, `FixedSizeBinary` widths {1, 7, 16, 33}, an in-progress
   batch mid-append, and type bounds including `Decimal128` ±(2¹²⁷−1).

7. **Wire your runner into `./gradlew check`**, as the C++ side does via the `cppConformance` task
   and `dev/run_cpp_conformance.sh`. A binding that is not run on every build will rot.

## Extending the format

**The one compatible change** is adding an optional field with a default to a `Layout.fbs` table. Old
readers ignore it; old segments still decode. The reserved family-watermarks region-table slot
(offset 320, currently 0/0) is the worked example of extension room designed in ahead of time.

**Everything else is a format break**: removing, renumbering or retyping a `Layout.fbs` field,
changing an enum's wire value, or changing any byte rule in this document — header offsets, catalog
layout, alignment, publication order, the type profile.

Breaking the format is allowed; doing it silently is not. The procedure:

1. Bump `FORMAT_VERSION` in `io.ascham.segment.SegmentFormat` **and** `cpp/src/format/arena_format.hpp`,
   and update the value in this document's [identifier table](#confirmed-identifiers).
2. Update this document and/or `Layout.fbs` — whichever is authoritative for the part you changed.
3. Regenerate the bindings if `Layout.fbs` changed: `dev/update_flatbuffers.sh`.
4. Regenerate the fixtures: `./gradlew regenerateGoldenCorpus regenerateLayoutVectors`. Review the
   diff — **any diff to `conformance/` is the format change**, and reviewing it is how you find out
   whether you changed more than you meant to.
5. Run `./gradlew check`, which runs both the Java and the C++ conformance halves.
6. Re-sync the downstream vendored copies (see `cpp/README.md`), in one commit naming the ascham
   commit it came from.

Because the golden corpus is checked in, an accidental format change shows up as an unexpected
`conformance/` diff rather than as a production misread months later. That is the point of it.
