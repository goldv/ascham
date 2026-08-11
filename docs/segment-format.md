# Arena segment format (v1)

Status: **M0 draft — review gate.** This is the on-disk / in-shared-memory byte contract. It is the
cross-language interface: the future C++ reader and the DuckDB tier are validated against segments
produced to this spec (the golden corpus), so **no offset, field, or ordering rule here may change
without a format-version bump** once any segment has been written.

Companion documents: [`../spec/ingest-arena.md`](../spec/ingest-arena.md) (requirements) and
[`arena-design-plan.md`](arena-design-plan.md) (design rationale and milestones). Where the design
plan sketched illustrative offsets, **this document is authoritative.**

## Confirmed identifiers

These were placeholders through M0; they were confirmed at the ascham rename. Changing any of the
first two again is a format break.

| Identifier | Value | Notes |
|---|---|---|
| Segment magic | `ASCHAMFM` (8 ASCII bytes) | Changing after any segment is written is a hard format break. |
| Metadata key prefix | `ascham.*` | Carried in the Arrow schema `custom_metadata`, so it is hashed into every header. |
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
| Layout descriptor (LayoutCodec)  |  64-aligned start; derived, written out for coupling-free readers
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
layout_off   = align64(schema_off + schema_len)    ; layout_len = |LayoutCodec bytes|
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
their own cache line to avoid false sharing (invariant / risk: the writer updates them while readers
poll them). All remaining bytes in each line are reserved and MUST be written as zero.

<!-- BEGIN GENERATED: header-table -->
| Offset | Size | Type | Field | Written | Ordering |
|---|---|---|---|---|---|
| 0 | 8 | 8×ASCII | `magic` = `ASCHAMFM` | at create | plain |
| 8 | 4 | int32 | `format_version` = 1 | at create | plain |
| 12 | 4 | int32 | `header_length` = 4096 | at create | plain |
| 16 | 32 | bytes | `schema_sha256` — SHA-256 of the canonical schema bytes | at create | plain |
| 48 | 8 | int64 | `segment_sequence` — monotonic per (table, rotation) | at create | plain |
| 56 | 8 | int64 | `arena_capacity` — total file size in bytes | at create | plain |
| 64 | 8 | int64 | `writer_epoch` — identifies the writing process instance | at create | plain |
| 72 | 8 | int64 | `batch_rows` — row capacity of each batch (`ascham.batch_rows`) | at create | plain |
| 80 | 8 | int64 | `batch_stride` — bytes reserved per batch | at create | plain |
| 88 | 40 | — | reserved (zero) | | |
| 128 | 8 | int64 | `heartbeat` — liveness counter (own cache line) | steady state | **release** / acquire |
| 136 | 56 | — | reserved (zero) | | |
| 192 | 8 | int64 | `active_batch_count` — number of catalog entries opened (own cache line) | per batch open | **release** / acquire |
| 200 | 56 | — | reserved (zero) | | |
| 256 | 8 | int64 | `schema_region_offset` | at create | plain |
| 264 | 8 | int64 | `schema_region_length` | at create | plain |
| 272 | 8 | int64 | `layout_region_offset` | at create | plain |
| 280 | 8 | int64 | `layout_region_length` | at create | plain |
| 288 | 8 | int64 | `catalog_region_offset` | at create | plain |
| 296 | 8 | int64 | `catalog_region_length` | at create | plain |
| 304 | 8 | int64 | `data_region_offset` | at create | plain |
| 312 | 8 | int64 | `data_region_length` | at create | plain |
| 320 | 8 | int64 | `family_watermarks_region_offset` (reserved, 0 in v1) | at create | plain |
| 328 | 8 | int64 | `family_watermarks_region_length` (reserved, 0 in v1) | at create | plain |
| 336 | 3760 | — | reserved (zero) | | |
<!-- END GENERATED: header-table -->

`schema_sha256` is re-verified at open against a SHA-256 computed over the embedded schema region; a
mismatch is a hard failure (invariant 7).

## Arrow schema region

The canonical Arrow IPC **schema message** bytes (`Schema.serializeAsMessage()` in Java), with all
`custom_metadata` — schema-level and field-level — emitted in sorted key order so the bytes, and
therefore `schema_sha256`, are insensitive to metadata insertion order. This makes the segment
self-describing: a reader parses the schema with a standard Arrow IPC reader and needs no build-time
coupling to the writer. `schema_sha256` in the header is the SHA-256 of exactly these bytes.

## Layout descriptor region (`LayoutCodec`)

The layout descriptor is *derived* from the schema, but written out so a reader (including one with
no Arrow dependency) can consume the byte layout directly without re-deriving it. Encoding is
little-endian, sequential, no padding:

| Order | Type | Field |
|---|---|---|
| 1 | int32 | `codec_version` = 1 |
| 2 | int32 | `batch_rows` |
| 3 | int64 | `batch_stride_bytes` |
| 4 | int32 | `family_count` |
| 5 | `family_count` × ( int32 `len` + `len` UTF-8 bytes ) | family names, indexed by `family_id` |
| 6 | int32 | `column_count` |
| 7 | `column_count` × *column record* | per-column layout, in schema ordinal order |

Each **column record**:

| Type | Field | Notes |
|---|---|---|
| int32 + bytes | `name` (len-prefixed UTF-8) | Arrow field name |
| int32 | `ordinal` | schema position |
| int32 | `kind` | `0`=FIXED, `1`=VARLEN, `2`=BOOL_BITMAP |
| int32 | `family_id` | index into the family-name table |
| int32 | `element_width` | fixed byte width; `0` for VARLEN/BOOL_BITMAP |
| int64 | `validity_offset` | batch-relative, 64-aligned |
| int64 | `data_offset` | batch-relative, 64-aligned (VARLEN: the byte data buffer) |
| int64 | `data_capacity_bytes` | |
| int64 | `offsets_offset` | batch-relative, 64-aligned; `-1` if not VARLEN |
| int64 | `varlen_capacity_bytes` | `ascham.varlen_bytes`; `0` if not VARLEN |

A reader may cross-check the descriptor against the embedded schema, but the descriptor is
authoritative for byte offsets.

## Batch catalog

A fixed array of `N = catalog_region_length / 64` entries, each exactly 64 bytes (one cache line, so
the single in-progress entry is naturally isolated from sealed ones — invariant: "one per cache
line"). Entry `k` describes batch `k`. Entries `[0, active_batch_count)` are live; the rest are
zero.

<!-- BEGIN GENERATED: catalog-table -->
| Offset | Size | Type | Field |
|---|---|---|---|
| 0 | 8 | int64  | `length` — bit 63 set = in progress; row count in bits 0..62; the segment's sole publication point |
| 8 | 8 | uint64  | `base_offset` — segment-relative offset of batch `k`'s data (`= data_region_offset + k * batch_stride`) |
| 16 | 8 | int64  | `ts_min` — min of `ascham.time_column` over the batch |
| 24 | 8 | int64  | `ts_max` — max of `ascham.time_column` |
| 32 | 8 | int64  | `stat_min` — min of `ascham.stats_column` (0 if no stats column) |
| 40 | 8 | int64  | `stat_max` — max of `ascham.stats_column` (0 if no stats column) |
| 48 | 8 | int64  | `seal_nanos` — wall-clock nanos at seal; 0 while in progress |
| 56 | 8 | — | reserved (zero) |
<!-- END GENERATED: catalog-table -->

**`length` and the in-progress bit.** Bit 63 set means the batch is still accumulating. The row count
is `length & Long.MAX_VALUE`. A negative sentinel is deliberately **not** used: `-0 == 0`, so a
zero-row in-progress batch would be indistinguishable from a sealed empty one (spec).

- In progress: `length = row_count | (1 << 63)`.
- Sealed: `length = row_count` (bit 63 clear).

`length` is the segment's sole publication point. Only `length` is written with release / read with
acquire; every other field in the entry is plain (see [Concurrency](#concurrency-contract)).

## Data region

Batch `k` occupies `[base_offset, base_offset + batch_stride)`. Rows accumulate **in place** at
capacity stride — sealing is not a copy (invariant 1: the writer never rewinds; rows below a
published count are immutable for the life of the segment). Wasted tail space per batch is
`batch_stride - used`, negligible when sealing on row count.

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

## Supported type profile (v1)

Accept exactly these; reject anything else at **load** with a clear error (never fail at append):

<!-- BEGIN GENERATED: type-profile -->
| Arrow type | Physical kind | Element width | Notes |
|---|---|---|---|
| `Bool` | BOOL_BITMAP | — |  |
| `Int8` | FIXED | 1 |  |
| `Int16` | FIXED | 2 |  |
| `Int32` | FIXED | 4 |  |
| `Int64` | FIXED | 8 |  |
| `UInt8` | FIXED | 1 |  |
| `UInt16` | FIXED | 2 |  |
| `UInt32` | FIXED | 4 |  |
| `UInt64` | FIXED | 8 |  |
| `Float32` | FIXED | 4 |  |
| `Float64` | FIXED | 8 |  |
| `Decimal128(38,9)` | FIXED | 16 |  |
| `Date32` | FIXED | 4 |  |
| `Time64(nanosecond)` | FIXED | 8 |  |
| `Timestamp(nanosecond, tz=UTC)` | FIXED | 8 |  |
| `Timestamp(microsecond, tz=UTC)` | FIXED | 8 |  |
| `Timestamp(nanosecond)` | FIXED | 8 |  |
| `Timestamp(microsecond)` | FIXED | 8 |  |
| `FixedSizeBinary(1)` | FIXED | 1 |  |
| `FixedSizeBinary(7)` | FIXED | 7 |  |
| `FixedSizeBinary(16)` | FIXED | 16 |  |
| `FixedSizeBinary(33)` | FIXED | 33 |  |
| `Utf8` | VARLEN | — | requires `ascham.varlen_bytes` |
| `Binary` | VARLEN | — | requires `ascham.varlen_bytes` |

Rejected in v1: `Float16`, `Decimal256`, `Date64`, `Time32`, `Time64`, `Timestamp`, `Timestamp`, `LargeUtf8`, `LargeBinary`, `Duration`, `Interval`, `Null`, `List`, `Struct`, `Map`, `Dictionary`.
<!-- END GENERATED: type-profile -->

Two rejections are deliberate design decisions, not omissions:

- **No nested types.** Hand-rolled layout for nested data is where correctness bugs live, and the
  reader must be implementable in C++ without an Arrow dependency.
- **No dictionary encoding.** Low-cardinality identifiers are stored as plain `Int32` codes and
  resolved against a separate reference-data table (via `ascham.ref`). Arrow's dictionary index is
  positional and stream-local, which breaks stable global identifiers, random access, and
  round-tripping to Parquet.

## Metadata keys

Carried in the Arrow schema `custom_metadata` (schema level) and each field's metadata (field level),
rather than a sidecar file — one artifact, and generic Arrow tooling can still read it. Validation is
strict and total: any unknown `ascham.*` key is an error.

<!-- BEGIN GENERATED: metadata-tables -->
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
<!-- END GENERATED: metadata-tables -->

## Invariants (correctness core)

Reproduced from the spec; each has format-level consequences pinned above and, in code, a comment
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
