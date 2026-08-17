# Using ascham from Java

How to write rows into an arena and read them back, using `:ascham-core`. For the byte format, the
concurrency protocol, and how to extend either, see
[`../format/segment-format.md`](../format/segment-format.md).

The model in one paragraph: a **table** is defined by an Arrow schema plus `ascham.*` metadata. One
writer thread appends rows into a shared-memory **segment**; readers in any process map the same
file and see rows as they are published, without copying and without coordinating with the writer.
Segments rotate on a time cycle or on capacity, so a table is a directory of segments.

## Setup

Java 21. Add the module:

```kotlin
dependencies {
    api(project(":ascham-core"))
}
```

**Every forked JVM needs three flags** — tests, benchmarks, your application, and your IDE run
configurations:

```kotlin
val aschamJvmArgs = listOf(
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)
```

The first one is the one that catches people out, and its failure mode is misleading. Agrona 2.x's
`UnsafeApi` references `jdk.internal.misc.Unsafe` *directly*, so `java.base` must **export** it — an
`--add-opens` is not enough, and what you get without it is a linkage `IllegalAccessError` at class
initialisation, not a reflection warning. The other two fix an Arrow `MemoryUtil` initializer error
and Agrona's mmap/unmap through `sun.nio.ch`.

Segments live in `/dev/shm`. In containers, check `--shm-size` (Docker defaults to 64 MB); the tests
take a segment directory override for exactly this reason.

## Define a table

Build an Arrow `Schema`, attach the `ascham.*` metadata, and load it. `ArenaSchema.load` is the only
way in, and it runs full validation:

```java
List<Field> fields = List.of(
        field("ts",  new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC"), Map.of()),
        field("sym", new ArrowType.Utf8(), Map.of(MetadataKeys.VARLEN_BYTES, "512")),
        field("px",  new ArrowType.Int(64, true), Map.of()));

ArenaSchema schema = ArenaSchema.load(new Schema(fields, Map.of(
        MetadataKeys.TABLE,          "quotes",
        MetadataKeys.SCHEMA_VERSION, "1",
        MetadataKeys.TIME_COLUMN,    "ts",
        MetadataKeys.STATS_COLUMN,   "px",
        MetadataKeys.BATCH_ROWS,     "65536")));
```

Schema-level keys (`MetadataKeys`):

| Key | Required | Meaning |
|---|---|---|
| `ascham.table` | yes | table name |
| `ascham.schema_version` | yes | integer, bump on any change |
| `ascham.time_column` | yes | timestamp column driving time-range pruning |
| `ascham.batch_rows` | no (default 65536) | target rows per sealed batch |
| `ascham.stats_column` | no | fixed-width integer column driving value-range pruning |

Field-level keys:

| Key | Applies to | Meaning |
|---|---|---|
| `ascham.varlen_bytes` | `Utf8`/`Binary` — required there, forbidden elsewhere | per-batch byte capacity |
| `ascham.sort_key` | any | integer ordinal, unique across columns |
| `ascham.family` | any | column family, default `base` (v1 writer accepts only `base`) |
| `ascham.ref` | signed `Int32` | ref-data table this code resolves against |

Keys outside the `ascham.` prefix are yours to use and pass through untouched.

**`ascham.varlen_bytes` is the one people get wrong.** It is a per-batch budget for the *whole
column*, not per row — size it as `batch_rows × max value size`. Undersize it and the writer migrates
rows into a new batch more often than it should; oversize it and every batch reserves memory it never
uses.

**Validation is total and happens at load, never at append.** `SchemaValidationException.errors()`
returns *every* problem at once rather than the first, so you fix them in one pass. Types outside the
[v1 profile](../format/segment-format.md#supported-type-profile-v1) throw
`UnsupportedTypeException`.

Worked example: `ascham-samples/src/main/java/io/ascham/samples/DemoSchemas.java`.

## Write

`RotatingWriter` is the entry point for anything long-lived. It owns the table directory, rotates on
the roll cycle or on capacity, and its appender never runs out of room — rotation is transparent and
exception-free, including when a row's varlen bytes overflow the last batch of a segment (the
partly-written row is adopted into the successor segment).

```java
SegmentDirectory dir = new SegmentDirectory(Path.of("/dev/shm/ito"), "quotes");
long epoch = dir.latestEpoch().orElse(0) + 1;   // bump on restart so readers see a new writer

try (RotatingWriter writer = RotatingWriter.open(
        dir, schema, /* maxBatches */ 4096, epoch,
        RollCycle.DAILY, Clock.systemUTC(), new SystemEpochNanoClock())) {

    Appender appender = writer.appender();      // one long-lived appender for the table
    UnsafeBuffer sym = new UnsafeBuffer("AAPL".getBytes(StandardCharsets.UTF_8));

    appender.beginRow();
    appender.setLong(0, nanoClock.nanoTime());
    appender.setBytes(1, sym, 0, sym.capacity());
    appender.setLong(2, 1_234_567L);
    appender.endRow();

    writer.heartbeat();                          // periodically, see below
}
```

`Appender` is the whole write surface: `beginRow()`, one setter (or `setNull`) per column,
`endRow()`. Setters are `setBool/setByte/setShort/setInt/setLong/setFloat/setDouble`,
`setDecimal128(col, low, high)`, `setFixedBytes(col, src, off, len)` and
`setBytes(col, src, off, len)` for varlen.

`SegmentWriter.createSegment(...)` is the single-segment primitive underneath. Use it directly only
if you are managing segment lifecycle yourself — its appender throws `SegmentFullException` when the
segment fills, which is exactly the case `RotatingWriter` exists to remove.

### Heartbeat

Call `writer.heartbeat()` on a timer (every ~100 ms is typical). It does two jobs: it advances the
liveness counter so readers can tell a quiet writer from a dead one, and it rotates a table that has
gone idle across a roll-cycle boundary. Without it, an alive-but-idle writer keeps the previous
interval's segment open indefinitely, and nothing downstream can treat that interval as complete.

### Rotation and retention

`RollCycle` is the interval a run of segments covers: `RollCycle.DAILY`, or `RollCycle.parse("4h")` /
`RollCycle.of(Duration)`. It must divide 24h evenly, so an interval never crosses a UTC day boundary.

`Retention` controls whether the writer unlinks its own old segments, and **the default,
`Retention.none()`, is a correctness position rather than a convenience**: count-based eviction
deletes the oldest segments knowing nothing about whether their rows were archived. Reclamation
belongs to whoever knows what has been persisted. `Retention.emergencyBackstop(n)` exists for
deployments with no archiver, or as a last line of defence against exhausting `/dev/shm` — every
eviction it performs logs at ERROR, because in an archived deployment it firing at all means data is
being dropped.

### Three things that will bite

1. **`close()` discards an open row.** If a row is begun and never ended, `RotatingWriter.close()`
   drops it with a WARNING and does not throw (close runs in `finally` blocks). The row was never
   published, so this is indistinguishable from crashing mid-row — but if you care, `endRow()` first.
2. **`rotate()` throws mid-row.** Forced rotation with a row open has no sane semantics, so it throws
   `IllegalStateException`. Open-row adoption is reserved for capacity-forced rotation, which the
   appender handles itself.
3. **Columns are addressed by ordinal, not name.** There is no name-keyed setter. A code-generated
   typed appender was built and removed: it had no consumers, and keeping every byte-write in one
   place is worth more than compile-time column-name checking.

### Staying allocation-free

The append path allocates zero bytes steady-state, and that is a tested gate — `AllocationTest`
measures it with `ThreadMXBean.getThreadAllocatedBytes` and asserts under 1.0 B/op. Keeping it that
way is a shared responsibility:

- Reuse `DirectBuffer`/`UnsafeBuffer` instances for varlen values; wrap your byte arrays once at
  startup, not per row. Never build a `String` per row on the hot path — the `Appender` has no
  `String` overload precisely so this is hard to do by accident.
- Reuse the appender. `writer.appender()` returns the same long-lived instance.
- Reuse whatever produces your rows. `MarketDataGenerator` in `:ascham-samples` returns a mutable
  `Event` and preallocated buffers, for this reason.

`./gradlew :ascham-core:jmh` runs the throughput and latency benchmarks; they are on-demand tooling,
not a gate.

## Read

Readers are independent — they map the file, freeze a view, and need no coordination with the writer
or with each other.

```java
try (SnapshotReader reader = SnapshotReader.open(segmentPath)) {
    Snapshot snapshot = reader.snapshot();

    for (BatchView batch : snapshot.batches()) {
        try (VectorSchemaRoot root = batch.root()) {   // zero-copy over mapped memory
            // ... read root, rows [0, batch.rowCount())
        }
    }
}
```

`SnapshotReader.open` verifies the magic and format version and **recomputes the schema SHA-256**
over the embedded schema region, throwing `SegmentFormatException` on mismatch. That check is
deliberately fatal: a reader misinterpreting a layout produces plausible garbage, which is the worst
failure mode available.

**A snapshot is frozen at construction and never re-read.** It acquire-loads the batch count and each
batch's row count exactly once. A stale snapshot is always safe — rows below a published count are
immutable for the life of the segment — but an inconsistent one is not, which is why there is no way
to refresh one. Call `reader.snapshot()` again for a newer view.

Two consequences worth expecting:

- A segment normally carries a trailing **empty in-progress batch** (row count 0), so `batchCount()`
  includes it. That is not a bug; sealing opens the next batch eagerly.
- `batch.root()` returns a `VectorSchemaRoot` whose buffers point straight into the mapping. Close it
  when done — closing releases only no-op buffers and the empty vector metadata — and do not let it
  outlive the `SnapshotReader`.

### Pruning

`snapshot.prune(TimeRange, StatRange)` filters batches on the catalog's min/max, before you touch any
data. A `null` range is a wildcard:

```java
List<BatchView> hits = snapshot.prune(new TimeRange(fromNanos, toNanos), null);
```

Only **sealed** batches are filtered. In-progress batches are always included, because their stats
are unpublished until seal — so pruning never hides fresh data.

### Liveness

```java
LivenessMonitor monitor = new LivenessMonitor(reader, Duration.ofSeconds(5), System::nanoTime);

monitor.poll();                 // ALIVE | STALLED, from heartbeat staleness
monitor.writerEpoch();          // changes when the writer restarts
monitor.inProgressRowCount();   // empty if the last batch is sealed
```

A frozen heartbeat past the stall threshold means the writer is gone. A heartbeat that keeps
advancing while `inProgressRowCount()` does not move means the writer is alive but not appending —
quiet, or stuck.

### Threading

One writer thread per table; two tables means two writers, each single-threaded. Readers are
independent of the writer and of each other, in this process or any other, and need no locks. The
protocol that makes this safe is in
[the format spec](../format/segment-format.md#concurrency-contract).

## Worked examples in the repo

| What | Where |
|---|---|
| Minimal end-to-end writer | `ascham-core/src/test/java/io/ascham/demo/LiveWriterMain.java` |
| Two tables, production-shaped | `ascham-samples/src/main/java/io/ascham/samples/MarketDataWriter.java` |
| Schema construction | `ascham-samples/src/main/java/io/ascham/samples/DemoSchemas.java` |
| Reader loop under concurrent writes | `ascham-core/src/test/java/io/ascham/conformance/SoakTest.java` |
| In-progress visibility, snapshot freezing, zero-copy | `ascham-core/src/test/java/io/ascham/read/` |

Runnable: `./gradlew :ascham-core:runLiveWriter --args="/dev/shm/ito quotes 200 30"`, and the demos
in [`../ascham-samples/README.md`](../ascham-samples/README.md).

## Gradle tasks

| Task | What it does |
|---|---|
| `:ascham-core:test` | unit + conformance tests (soak excluded) |
| `:ascham-core:soakTest -Dio.ascham.soak.seconds=N` | one writer, N readers, extended duration |
| `:ascham-core:jcstress` | the memory-ordering harness (quick mode) |
| `:ascham-core:jmh` | append and snapshot benchmarks |
| `:ascham-core:runLiveWriter` | minimal live writer |
| `check` | everything above except soak/jmh, plus the C++ conformance runner |
| `regenerateGoldenCorpus`, `regenerateLayoutVectors` | manual; any diff is a format change |
