# Arena ingest layer — design & implementation plan

Companion to [`spec/ingest-arena.md`](../spec/ingest-arena.md), which is the authoritative
requirements spec. This document records the design decisions, module architecture, key
mechanisms, and milestone-by-milestone implementation plan for building the arena: a
single-writer, multi-reader, shared-memory columnar ingest layer in `/dev/shm` whose byte layout
is derived entirely from an Apache Arrow schema. The exact byte-level format contract is the M0
deliverable (`format/segment-format.md`), produced from §3–§4 of this plan and reviewed before any
implementation code is written.

---

## 1. Decisions on the spec's open questions

The spec's "raise before implementing" items, resolved by following the spec as written; each is
overridable at review:

1. **Decimal128** — in v1 (16-byte fixed-width column), per the spec's type profile.
2. **Unsigned ints** — in v1. Layout identical to signed; downstream DuckDB/Parquet mapping cost accepted.
3. **Timestamps** — per-column `ns` or `us` as declared; storage is Int64 either way.
4. **Varlen capacity seals** — yes: a batch seals when row count reaches `ascham.batch_rows` *or*
   any varlen column's byte capacity would be exceeded, whichever binds first (spec default).
5. **Stats** — one designated stats column; fixed 64-byte catalog entries.
6. **Rotation** — format is rotation-agnostic; v1 policy is one segment per table per UTC day,
   with `rotate()` also firing on capacity exhaustion. Full rotation/retention/liveness is M5.
7. **Placeholders** — resolved at the ascham rename: `ASCHAMFM` magic, `ascham.*` metadata prefix,
   and the `io.ascham` base package are now confirmed (a magic change after any segment is written
   is a format break).

**One spec deviation requiring sign-off:** the spec bans `MappedByteBuffer` ("2 GB cap") and
simultaneously mandates `VarHandle` `getAcquire`/`setRelease` — but VarHandles need a `ByteBuffer`
coordinate; they cannot address raw Agrona-mapped memory. Resolution (§4a): only the small
control region (header + catalog, KBs–MBs) is mapped as a `MappedByteBuffer` and accessed via one
`byteBufferViewVarHandle`; the data region — the only region the 2 GB cap actually threatens — is
mapped with Agrona and uses plain stores only, made visible by the catalog release-store.

## 2. Build layout

Multi-module from day one (future `:ascham-query` tiers slot in beside `:ascham-archive`):

- **`:ascham-core`** — the library, plus JMH via the `me.champeau.jmh` plugin (`src/jmh`) and the
  jcstress harness (`src/jcstress`). Benchmarks want package-private access to writer internals, so
  no separate bench module; and the jcstress plugin confines its annotation processor to the
  `jcstressAnnotationProcessor` configuration, so it never reaches the library's `compileJava` —
  which is what previously justified a separate `:arena-jcstress` subproject.

`settings.gradle.kts`: `include("ascham-core")`.

`gradle/libs.versions.toml` (resolved against Maven Central / the Plugin Portal at kickoff, 2026-07):
agrona 2.5.0, arrow 19.0.0 (`arrow-vector`, `arrow-memory-core`, `arrow-memory-unsafe` runtime-only,
`arrow-c-data`), junit-bom 5.14.4, assertj 3.27.7, jmh 1.37 + plugin `me.champeau.jmh` 0.7.3,
jcstress 0.16 + plugin `io.github.reyerizo.gradle.jcstress` 0.9.0. The jmh/jcstress entries
are added at their milestones (M2 bench, M4 concurrency) to keep the early build lean.

JVM flags, applied to test/jmh/jcstress forks. The first is load-bearing and non-obvious: Agrona
2.x's `UnsafeApi` references `jdk.internal.misc.Unsafe` directly, so `java.base` must **export** it
(an `--add-opens` is insufficient — the failure is a linkage `IllegalAccessError`, not reflection).
The Arrow opens fix a `MemoryUtil` initializer error; `sun.nio.ch` is for Agrona's mmap/unmap (M2+):

```
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```

Tests additionally get `-Darrow.memory.debug.allocator=true` and a configurable segment dir
(`-Dio.ascham.segment.dir=build/segments`) so CI does not depend on `/dev/shm` size (Docker
defaults `--shm-size` to 64 MB); only the soak/smoke tests insist on real `/dev/shm`, with a
size preflight-skip. Java 21 toolchain, no preview features.

## 3. Package & class design

Base package `io.ascham`:

```
io.ascham.schema   — load, validate, canonicalise Arrow schema + ascham.* metadata, SHA-256
io.ascham.layout   — pure schema → byte-layout function + descriptor codec
io.ascham.segment  — format constants, header/catalog codecs, mapping, rotation (M5)
io.ascham.write    — SegmentWriter, BatchCursor, Appender, GenericAppender, RollingAppender
io.ascham.read     — SnapshotReader, Snapshot, BatchView, pruning, liveness
io.ascham.util     — Alignment, Sha256
```

### schema (M1)

- `ArenaSchema` — validated pairing of Arrow `Schema` + parsed metadata; `static ArenaSchema load(Schema)`.
- `ArenaMetadata` (record) — `table, schemaVersion, batchRows (default 65536), timeColumn, statsColumn`.
- `ColumnMetadata` (record) — `varlenBytes, sortKey, family (default "base"), ref`.
- `TypeProfile` — the v1 whitelist; `classify(Field) → PhysicalKind`, throws on rejected types with
  the spec's documented rationale for nested/dictionary rejections.
- `SchemaValidator` — **total** validation: collects *every* error (Utf8 without `ascham.varlen_bytes`,
  `ascham.time_column` not a timestamp, `ascham.stats_column` not a fixed-width integer, `ascham.ref`
  on non-Int32, duplicate sort keys, unknown `ascham.*` keys…) and throws once with the full list.
  Fail at load, never at append.
- `CanonicalSchema` — canonical IPC schema bytes with metadata keys sorted at both levels (hash
  insensitive to map order); `sha256(ArenaSchema)`.

### layout (M1)

- `PhysicalKind` enum: `FIXED | VARLEN | BOOL_BITMAP`.
- `ColumnLayout` (record) — kind, familyId, elementWidth, validityOffset, dataOffset,
  dataCapacityBytes, offsetsOffset (−1 if fixed), varlenCapacityBytes. All offsets batch-relative,
  all 64-byte aligned (invariant 5).
- `LayoutDescriptor` (record) — columns, batchRows, `batchStrideBytes`, families. Stride rounded
  up to 4096 so a batch never ends flush against an unmapped page (invariant 6).
- `Layouts.compute(ArenaSchema) → LayoutDescriptor` — the pure deterministic function; buffers in
  column-ordinal order (validity, data, offsets), each base 64-aligned.
- `LayoutCodec` — fixed little-endian encode/decode for the segment's descriptor region; round-trip
  is identity (readers need no build-time coupling).

### segment (M2)

- `SegmentFormat` — every constant in one file, each commented with a pointer to
  `format/segment-format.md` and the invariant it serves: `MAGIC="ASCHAMFM"`, `FORMAT_VERSION=1`,
  `HEADER_LENGTH=4096`; header offsets (magic 0, version 8, headerLen 12, schemaSha256 16,
  segmentSeq 48, capacity 56, epoch 64, **heartbeat 128 — alone on its cache line**, region table
  192 with a **reserved family-watermarks slot**, §4c); `CATALOG_ENTRY_SIZE=64` with entry offsets
  (length 0, base 8, tsMin 16, tsMax 24, statMin 32, statMax 40, sealNanos 48, 8 spare);
  `IN_PROGRESS_BIT = 1L << 63`.
- `ControlRegion` — *the* acquire/release surface: wraps the `MappedByteBuffer` over
  `[0, dataOffset)`; `getLongAcquire/putLongRelease` via one static
  `MethodHandles.byteBufferViewVarHandle(long[].class, LITTLE_ENDIAN)`, plus plain get/put.
  Constructor alignment probe + negative test (misaligned acquire/release throws at runtime).
- `SegmentHeader` / `CatalogCodec` — field codecs over `ControlRegion`; `CatalogCodec.publishLength`
  is the **only** release-store on the append path; `SegmentHeader.verifyMagicVersionHash` is the
  invariant-7 hard failure at open.
- `SegmentFile` — owns the `FileChannel`, control-region mapping, and Agrona `UnsafeBuffer` over
  the data region (>2 GB capable); `create(Path, size)` / `openReadOnly(Path)`. Creation writes to
  a temp file and atomically renames, so a reader can never map a half-initialised header.

### write (M2)

- `SegmentWriter` (AutoCloseable) — `createSegment(Path, ArenaSchema, capacityBytes, epoch, EpochNanoClock)`,
  `appender()`, `seal()`, `rotate()`, `heartbeat()`, `close()`. The injected Agrona
  `EpochNanoClock` is what makes golden-corpus `seal_nanos`/epoch deterministic. Rejects schemas
  with >1 family at create ("multi-family write is post-v1"), never at append.
- `BatchCursor` — **sole owner of the publication protocol** (invariants 1–3 live here and only
  here): batch base offset, rowIndex, precomputed per-column absolute addresses, writer-local
  `varlenEnd[]` and running ts/stat min/max (heap, never in the shared line). See §4c for
  `endRow()` and the varlen-exhaustion `migrateOpenRow()`. **Package-internal** (package-private
  class and methods): reached only through the two appenders and `SegmentWriter`, so producers
  cannot drive `seal`/`openBatch` or reorder publication — the appenders are the only way to write
  rows, `SegmentWriter` the only way to drive lifecycle.
- `Appender` (interface) — the finalized row-writing contract: `beginRow()`, `setBool/setByte/…/setLong`,
  `setFloat/setDouble`, `setDecimal128(col, low, high)`, `setFixedBytes/setBytes(col, DirectBuffer, off, len)`,
  `setNull(col)`, `endRow()`.
- `GenericAppender` — descriptor-driven `Appender` bound to one segment (`SegmentWriter.appender()`,
  one cached instance per writer). Delegates all publication to `BatchCursor`.
- `RollingAppender` — `Appender` spanning rotations (`RotatingWriter.appender()`). Rotation is
  decided exception-free at `beginRow` (time policy, row-count capacity) and at `setBytes` (varlen
  exhaustion in the last batch, where the open row is adopted into the successor segment via
  `BatchCursor.adoptOpenRowFrom`); producers never see `SegmentFullException` and never replay rows.

### read (M3)

- `SnapshotReader` — `open(Path)`: map read-only, verify magic/version/SHA-256 (invariant 7),
  decode embedded schema + descriptor; `snapshot()`, `writerEpoch()`, `heartbeat()`.
- `Snapshot` — frozen at construction: acquire-loads every catalog `length` **exactly once** into
  a `long[]`, resolves row counts (`length & ~IN_PROGRESS_BIT`, through the `Watermarks` seam,
  §4c); never re-reads. `batches()`, `prune(tsRange, statRange)`.
- `BatchView` — `rowCount()`, `sealed()`, stats accessors, `root()`: wraps each column buffer as
  `new ArrowBuf(ReferenceManager.NO_OP, null, len, address)` + `loadFieldBuffers` into a
  `VectorSchemaRoot`. Never touches an Arrow allocator. (Verify at M3 whether Arrow 18 accepts
  nullCount −1 in `ArrowFieldNode`; if not, compute lazily from the validity bitmap.)
- `Pruning` — sealed-entry min/max overlap test; **in-progress batches are never pruned** (their
  stats are unpublished until the bit-63-clearing release — the reason non-`length` catalog fields
  need no ordering; documented in M0, pinned by jcstress case 2).
- `LivenessMonitor` (M5) — epoch/heartbeat staleness; stuck-in-progress detection (length
  unchanged while heartbeat advances).

## 4. Key mechanism decisions

### 4a-bis. Mapping mechanism (M2 implementation note — Agrona 2.x reality)

The spec names `MappedResizeableBuffer` for >2 GB mappings, but that class was **removed in Agrona
2.x**, and Agrona's `UnsafeBuffer` caps at an `int` capacity (~2 GB). On Java 21 the only other
routes to a single >2 GB mapping are the FFM `MemorySegment` (preview — banned) or reflective
`FileChannelImpl.map0` (fragile). So v1 makes a deliberate, isolated choice: **a segment's data
region is capped at 2 GB, enforced at `createSegment`, and larger tables are handled by
capacity-triggered rotation** (which the design already provides) — a logical table spans multiple
≤2 GB segments, exactly as the multi-segment reader tier already expects. The whole file is mapped
once as a direct buffer; `ControlRegion` (VarHandle) provides ordered access and an Agrona
`UnsafeBuffer` over the same memory provides plain/bulk data access. This leaves the on-disk byte
format (`format/segment-format.md`) unchanged and confines the limit behind `SegmentFile`, so a true
large-mapping backend (reflective `map0`, or FFM once non-preview on the target JDK) can drop in
without touching the format or the writer/reader. `SegmentWriter.createSegment` rejects a requested
capacity above `Integer.MAX_VALUE` with a message pointing at rotation.

### 4a. Cross-process ordering: one VarHandle over a dedicated control-region mapping

Split mapping as described in §1. Justification: (i) all publication funnels through the catalog
`length` — every plain data/offsets store sequenced before `putLongRelease(length)` is made
visible by the reader's `getLongAcquire(length)`, which is exactly invariant 2, so the data region
needs no ordered access at all; (ii) the control region is structurally small (header 4 KiB +
64 B/batch — even a pathological 1M-batch segment is a 64 MiB catalog), so the 2 GB cap is moot
there; (iii) VarHandle is the literal spec requirement, and Agrona's `getLongAcquire/putLongRelease`
sit on `sun.misc.Unsafe`, which is on the JEP 471/498 removal path — confining all ordered access
to `ControlRegion` also makes any future swap a one-class change. Ordered fields, exhaustively:
catalog `length` (release/acquire), header heartbeat (release/acquire). Epoch/region table are
plain-written before the atomic rename at create.

### 4b. Typed appenders: removed

M2 shipped a build-time source generator (`TypedAppenderGenerator` + `RowAppender` base) producing
per-schema typed appenders (`setBidPx(long)` …) byte-identical to `GenericAppender`. It was removed
once the `Appender` interface landed: it had no consumers, was never wired into any build, and the
generic path is already allocation-free (the `AllocationTest` gate), so the typed layer bought only
compile-time column-name checking at the cost of a second write path and an equivalence suite. All
byte-writing stays in the single package-private `BatchCursor`, reached only through the `Appender`
implementations — the property the codegen design existed to protect.

### 4c. Writer accumulation, invariant-2 enforcement, families

**In-place at capacity stride.** Batch *k* lives at `dataOff + k·batchStrideBytes`, computed when
the batch opens, never moved. Opening writes `base_offset`/zeroed stats/`seal_nanos=0` with plain
stores, then release-publishes `0 | IN_PROGRESS_BIT` — a reader acquiring the in-progress length
always finds `base_offset` published. `seal()` = plain-store final stats + `seal_nanos`, then one
release-store clearing bit 63, then open batch *k+1*. No copies anywhere.

Note: `seal()` (and the row-count seal) always opens the next batch eagerly, so after an explicit
seal a segment carries a trailing empty in-progress batch (row count 0). This is expected — readers
tolerate empty batches, and the empty in-progress batch is never pruned — but it means a snapshot's
`batchCount` includes that trailing batch. Batch 0 is opened at `createSegment`.

**Invariant 2 by code structure, not discipline** — `endRow()` is the only method that touches the
catalog:

```java
void endRow() {
  for (int c = 0; c < varlenCols; c++)                 // (1) offsets[n+1] for EVERY varlen column
    offsets(c).putInt((rowIndex + 1) << 2, varlenEnd[c]); //  (unset ⇒ end==start ⇒ empty — nulls free)
  flushValidityBits();                                 // (2) set-only byte RMW — invariant 3
  updateRunningStats();                                // (3) writer-local heap state only
  rowIndex++;
  catalog.publishLength(batch, rowIndex | IN_PROGRESS_BIT); // (4) the ONE release-store — last
}
```

`putVarlen` copies bytes and bumps writer-local `varlenEnd[c]` — it never writes the offsets array.

**Varlen-exhaustion seal without rewind:** exhaustion surfaces mid-row in `putVarlen`. The open
row's bytes are unpublished and therefore dead, so `migrateOpenRow()`: seal at `rowIndex`
(completed rows only), open the next batch, memcpy the open row's fixed cells and per-column
contiguous varlen bytes to row 0 of the new batch (Agrona, zero-alloc, invisible to readers),
retry. Row-count seal is the cheap `beginRow()` check.

**Families (invariant 8):** modeled from day one — `ColumnLayout.familyId`, descriptor `families`
list, a reserved `family_watermarks` region-table slot (0/0 in v1, specified in
`format/segment-format.md` for a future format version), and one reader seam:
`rowCount[k] = watermarks.resolve(k, rawLength[k])` where v1 `Watermarks` is identity and a future
one takes min across the family table. v1 writer rejects >1 family at create.

### 4d. Golden corpus

Repo-root **`conformance/`** (cross-language contract — the future C++ reader must not reach into
Java test resources): `schemas/*.arrows` (serialised IPC schema messages), `golden/<case>/segment.bin`
+ `expected.json` (row-level values), `golden/manifest.json` with per-case
`{name, schema, seed, epoch, clockStartNanos, clockStepNanos, ops, segmentSha256}`. Determinism:
`SplittableRandom(seed)` data generator + injected fake `EpochNanoClock` (this is why
`createSegment` takes the clock). Tiny capacities (2–4 batches × 16–64 rows, varlen caps of a few
hundred bytes) keep files at KBs and exercise exhaustion paths. `regenerateGoldenCorpus` Gradle
task writes `conformance/` (manual, diff-reviewed — any diff is a format change);
`GoldenCorpusTest` in CI regenerates into `build/`, byte-compares against checked-in files, then
reads the checked-in files back through `SnapshotReader` asserting `expected.json`. Case list per
spec: every supported type, empty batch, all-null / all-non-null column, varlen at exact capacity,
varlen empty strings, in-progress batch mid-append, `FixedSizeBinary` widths {1, 7, 16, 33},
min/max at type bounds (Decimal128 ±(2¹²⁷−1), unsigned maxima).

## 5. Milestones

**M0 — format spec (stop for review).** Write `format/segment-format.md`: exact header/catalog byte
tables from §3, bit-63 semantics, little-endian, 64-byte alignment + 4 KiB stride rules, the eight
invariants verbatim with format-level consequences, type profile + the two documented rejections
(nested, dictionary), metadata key table, create-time temp-file+rename atomicity, in-progress
pruning exemption, placeholder confirmations (`ASCHAMFM`, `ascham.*`, `io.ascham`). Gradle
skeleton (settings, toml, empty modules) also lands here — it isn't implementation code.
**Gate: spec review sign-off before M1.**

**M1 — schema + descriptor (pure, no I/O).** Order: `TypeProfile` → metadata records →
`SchemaValidator` → `ArenaSchema.load` → `CanonicalSchema` → `Layouts` → `LayoutCodec`.
Tests: `TypeProfileTest` (accept/reject matrices), `SchemaValidatorTest` (one method per rule; all
errors in one throw), `CanonicalSchemaTest` (metadata-order permutations → identical sha256),
`LayoutDeterminismPropertyTest` (hand-rolled: `RandomSchemaGenerator` over 10k seeds, `compute`
twice ⇒ equal + byte-identical encoding — no jqwik dep), `LayoutAlignmentTest` (offsets %64==0,
stride %4096==0), `LayoutCodecRoundTripTest`.

**M2 — writer.** Order: `SegmentFormat` + `ControlRegion` (alignment probe) → header/catalog
codecs → `SegmentFile` → `BatchCursor` → `SegmentWriter` + `GenericAppender` → seal paths.
Tests: `ControlRegionTest`, `SegmentCreateTest` (atomic create), `GenericAppenderTest`
(per-type round-trip via raw buffer reads — reader doesn't exist yet), `SealOnRowCountTest`,
`VarlenCapacitySealTest` (exact fit, one-byte-over, `migrateOpenRow`), `NullHandlingTest`,
`MultiFamilyRejectedAtCreateTest`. (The typed-appender generator and its equivalence suite were
built here and later removed — §4b.)

**M3 — reader.** `SnapshotReader.open` + hash check → `Snapshot` freezing → `BatchView` Arrow
wrapping → pruning. Tests: `SchemaHashMismatchTest`, `SnapshotFreezeTest` (append after snapshot;
snapshot re-read bit-identical), `InProgressVisibilityTest`, `BatchViewArrowValuesTest` (every
type via `VectorSchemaRoot`, nulls included), `PruneTest` (in-progress never pruned),
`ZeroCopyTest` (`ArrowBuf.memoryAddress()` lies inside the mapping).

**M4 — conformance + concurrency.**

- Golden corpus per §4d. **As built:** `conformance/` holds 8 cases (`GoldenCases`) covering every
  type plus empty batch, all-null, varlen exact-capacity + migration, varlen empty, FixedSizeBinary
  widths {1,7,16,33}, in-progress mid-append, and type bounds (incl. Decimal128 ±(2¹²⁷−1)).
  `GoldenCorpusTest` regenerates each case, byte-compares to the checked-in `.bin`, and reads it back
  through `SnapshotReader`. `regenerateGoldenCorpus` is the one-command regen. (Deferred: a language-
  neutral `expected.json` of row values — byte-stability + Java read-back is the v1 check; the `.bin`
  files are already the cross-language contract.)
- **jcstress** (`ascham-core/src/jcstress`): `OffsetsBeforeLengthTest`, `SealBit63Test`,
  `ValidityByteRmwTest`, `RowCountMonotonicTest` — all 4 pass (no forbidden outcomes). **As built:**
  they exercise the `getAcquire`/`setRelease` JMM contract directly via `VarHandle` over plain
  arrays — the exact primitive `ControlRegion` wraps — so they're self-contained (no
  direct-buffer-per-state cost, no dependency on the main source set). The plugin isn't
  configuration-cache compatible, so the run task is marked
  `notCompatibleWithConfigurationCache` (degrades gracefully; cache stays on repo-wide). PR CI runs
  `-m quick`; nightly runs default mode.
- **JMH** (`src/jmh`, `me.champeau.jmh` plugin): `AppendBenchmark` (`appendRow` = full 14-column row;
  `appendBatchAndSeal` = seal latency) and `SnapshotBenchmark` (`@Param batchCount ∈ {16,256,4096}`).
  **Zero-alloc red/green gate as built:** `AllocationTest` (a normal CI test) measures the append
  hot path with `ThreadMXBean.getThreadAllocatedBytes` and asserts `< 1.0 B/op` — more deterministic
  than a forked JMH GC-profiler run and it measures the invariant directly; the JMH benchmarks are
  on-demand throughput/latency tooling (`./gradlew :ascham-core:jmh`), not a gate.
- **Soak** (`SoakTest`): 1 writer + N readers over one segment; each row encodes its global index in
  three columns and readers verify internal consistency (no torn reads), monotonic totals, and
  frozen-snapshot stability. `smoke` (0.5 s) runs in the normal suite; `soak` is `@Tag("soak")`, run
  by the `soakTest` task (duration via `-Dio.ascham.soak.seconds`).

**M5 — rotation + liveness.** `SegmentDirectory` (`<baseDir>/<table>/<yyyyMMdd>.<seq>.ascham` for
the daily cycle, `<yyyyMMdd>.<HHmm>.<mins>m.<seq>.ascham` for sub-day cycles; `/dev/shm/ito` in
production) — naming, listing, `nextSeq`, `unlink` (= `shm_unlink`), and a header
`readEpoch`/`latestEpoch`. `RollCycle` (a duration dividing 24h evenly; `DAILY` default) replaces
the original `RotationPolicy`/`DailyRotationPolicy` pair — the cycle is encoded in segment names,
so an off-grid pluggable policy would desynchronise names from data. `LivenessMonitor`
(reader-side) — `poll()` → `ALIVE`/`STALLED` off heartbeat staleness, `writerEpoch()` for restart
detection, `inProgressRowCount()` for stuck detection.

**As built:** the append surface is `RotatingWriter.appender()` — a long-lived `RollingAppender`
the producer drives directly (`beginRow` … setters … `endRow`); rotation is transparent and
exception-free. Time-based rotation is one long comparison against the interval-end boundary,
checked at `beginRow` and `heartbeat` (allocation-free per row);
**capacity rotation is automatic** — `beginRow` rotates when the catalog would fill
(`BatchCursor.rowCountRotationDue`), and a mid-row varlen exhaustion in the last batch adopts the
open row into the successor segment (`adoptOpenRowFrom`; the only case not predictable at
`beginRow`). `SegmentFullException` survives solely as the raw single-segment `SegmentWriter`
backstop. Rotating closes only the writer's mapping; readers hold independent mappings and the file
survives (unlinked later by retention, kept alive for mapped readers by the kernel refcount).
Restart bumps the epoch off `directory.latestEpoch()+1`. Tests: `RotationTest` (day boundary, fake
clock, mid-row heartbeat/rotate/close guards), `RowCountRotationTest` (exception-free capacity
rollover), `MidRowVarlenRotationTest` (cross-segment open-row adoption, doomed-row rejection,
intra-segment boundary), `RollingAppenderAllocationTest` (rolling path stays allocation-free),
`RetentionUnlinkTest` (pre-unlink reader still reads; fresh open of the unlinked path fails),
`EpochBumpOnRestartTest`, `LivenessTest` (heartbeat ALIVE/STALLED + in-progress row count).

## 6. Risks

1. **Arrow JPMS friction on JDK 21** — `--add-opens` needed in every forked JVM (test/jmh/jcstress/IDE);
   centralise the flag list once.
2. **`sun.misc.Unsafe` sunset (JEP 471/498)** — Agrona and `arrow-memory-unsafe` sit on it; fine on
   21, pin the toolchain, treat JDK bumps as deliberate migrations. Our ordering code is
   VarHandle-only by design.
3. **False sharing** — heartbeat alone at offset 128 by construction; the in-progress catalog line
   is written per `endRow` while readers poll it (inherent); keep writer-local state on heap and
   leave the entry's 8 spare bytes alone.
4. **Torn/stale non-`length` catalog fields** — safe only because `base_offset` publishes before
   the first IN_PROGRESS release and stats/`seal_nanos` are meaningful only after bit-63 clears;
   this reasoning goes in `format/segment-format.md`, jcstress case 2 pins it.
5. **/dev/shm in CI** — 64 MB Docker default; configurable segment dir for correctness tests, tiny
   capacities + preflight-skip for shm-dependent ones.
6. **`byteBufferViewVarHandle` alignment traps** — runtime throws on misalignment; constructor
   probe + negative test retire this at M2.
7. **Arrow foreign-buffer wrapping sharp edges** — `ArrowFieldNode` nullCount, `TypeLayout` buffer
   size expectations; `ZeroCopyTest`/`BatchViewArrowValuesTest` catch at M3, budget slack there.
8. **Golden-corpus churn** — any format tweak invalidates all golden files (intended); one-command
   regeneration, small files, manifest sha256s make accidental regeneration obvious.
9. **Gradle configuration-cache vs jmh/jcstress plugins** — *resolved:* `me.champeau.jmh` 0.7.3 is
   config-cache compatible on Gradle 9.5.1; the `io.github.reyerizo.gradle.jcstress` 0.9.0 run task
   is not, so it's marked `notCompatibleWithConfigurationCache` (graceful degrade for that task; the
   cache stays enabled repo-wide).
