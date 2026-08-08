# ascham-archive

The cold tier: rolls completed roll intervals out of the shared-memory arena into Parquet under an
Apache Iceberg catalog — the kdb rdb→hdb write-down. Design:
[`../docs/cold-tier-design-plan.md`](../docs/cold-tier-design-plan.md).

The roll is pure Java on the native Iceberg API. Segments are read through their zero-copy Arrow
roots (mmap, no copy), each file group is ordered by an index sort, rows stream through the Iceberg
parquet writer, and one transaction commits the whole interval. DuckDB is no longer involved in
writing — it remains the query-side engine, and the dev stack verifies it can read everything the
roll writes.

The unit of the roll is the writer's **roll cycle** (arena `RollCycle`): a duration dividing 24h
evenly — 4h, 6h, 12h, 1d (the default). The writer encodes each segment's interval in its file name
(`yyyyMMdd.<seq>.ascham` for daily, `yyyyMMdd.HHmm.<mins>m.<seq>.ascham` for sub-day), and the roller
groups and commits purely from those names. Iceberg partitioning stays daily whatever the cycle —
an interval never crosses midnight — so sub-day cycles change commit granularity and file sizing,
not the table layout.

The destination is one string: a local path (or `file://`) rolls straight to disk through a
serverless Hadoop catalog — no services at all — while an `http(s)://` URI is an Iceberg REST
catalog (the dev stack's Lakekeeper), which owns the object storage underneath. A bare `s3://`
warehouse is rejected by design: Hadoop-catalog commits rename metadata files, which is not atomic
on object stores; S3 data goes through a REST catalog.

## Status

| Piece | State |
|---|---|
| `TableRoller` — the roll protocol: discover → freeze check → verify → roll ascending | **Implemented & tested** |
| `ArenaInventory` — pending intervals, the freeze check, I2 interval-alignment verification | **Implemented & tested** |
| `IcebergRollExecutor` — native table create, group write, atomic interval commit + watermark | **Implemented & tested** |
| `SegmentGroup` / `GroupSorter` — zero-copy group addressing, the index sort | **Implemented & tested** |
| `IcebergTypes` — arena → Iceberg types, with the widening conversions | **Implemented & tested** |
| `RollService` / `RollScheduler` — multi-table passes, pressure alert, scheduling + backoff | **Implemented & tested** |
| Reclamation utility (unlink archived segments from recorded provenance) | Not started — see below |
| Unified realtime + historical query surface | Not started (R6) |

## The protocol in one paragraph

An interval is rollable only when it is complete (its declared end has passed) and no writer can
still append to it: either a segment of a later interval exists (the normal case — the writer
rotates at the cycle boundary even when idle), or the writer's heartbeat has stopped. Before
copying, every batch's zone map is checked to prove each segment's rows really fall inside the
interval its name declares. The interval's parquet files, its segment provenance, and the advanced
watermark are committed in **one atomic transaction**, so a partially-rolled interval cannot exist
— a crash mid-interval commits nothing and the next run simply rolls it again. Intervals are rolled
**oldest first and the run stops at the first failure**, which is what lets a single watermark stand
in for the whole set. If a writer restarts mid-interval with a different cycle, the overlapping
declared intervals are merged and committed as one unit (never spanning a day), and segments already
below the watermark within a merged unit are skipped rather than duplicated.

State lives in the table itself, in two places with two jobs:

| Where | What | Why it lives there |
|---|---|---|
| Table property `ascham.rolled-through` | The watermark: the instant the table is fully committed through (ISO instant; older tables' bare ISO date reads as end-of-that-day and upgrades on the next commit) | Correctness. Updated atomically with the data; survives snapshot expiration |
| Table property `ascham.ascham-dir` | Which arena owns this table | Verified on every open — two arenas must never share a table |
| Snapshot summary `ascham.day` / `ascham.interval` / `ascham.segments` / `ascham.ascham-dir` / `ascham.rows` | Which interval and segment files fed each commit | Provenance for the reclaim utility (audit, not correctness) |

## File sizing

The primary file-size dial is the **roll cycle**: by default all of an interval's segments land in
one parquet file, so a shorter cycle (4h, 6h) means smaller, more frequent files and a 1d cycle
means one file per day. When capacity rotation packs more segments into an interval than one file
should hold, `maxSegmentsPerFile` caps how many consecutive segments each parquet file takes
(below 1 — the default — means no cap). At ~115 MB segments, `2` gives ~230 MB files; scale toward the 128–512 MB (up to 1 GB
for very large tables) guidance. Each group is sorted by the table's sort columns (configured, or
the schema's own `ascham.sort_key` declaration, or the time column) via an index sort: only the
sort-key columns are pulled onto the heap, the permutation is sorted, and rows stream out of the
mmap in order — memory is bounded by one group's keys, not its data. Created tables also declare
`write.target-file-size-bytes` and `write.parquet.compression-codec=zstd`.

## Usage

```java
ArchiveConfig config = ArchiveConfig.builder()
        .arenaBaseDir(Path.of("/dev/shm/ito"))
        .destination("/data/warehouse")            // or "http://localhost:8181/catalog"
        .build();                                  // sort order comes from ascham.sort_key

try (RollExecutor executor = new IcebergRollExecutor(config)) {
    TableRoller.RollResult result = new TableRoller(config, executor).roll("quotes");
    // result.rolled() — intervals copied by this run; result.totalRows() — rows written
}
```

`TableRoller.cutover(table)` returns the instant the unified query surface splits on: historical
data covers everything before it, the arena serves everything from it onward.

For a whole deployment, `RollService` does every table in one pass and `RollScheduler` runs it:

```java
RollService service = new RollService(config, executor, 8L << 30);
try (RollScheduler scheduler = new RollScheduler(service, LocalTime.of(0, 15), Clock.systemUTC())) {
    scheduler.start();   // drains any backlog now, then runs daily at 00:15 UTC
}
```

When writers roll sub-day cycles, schedule on a fixed cadence instead — roughly the shortest cycle
(a pass with nothing pending is near-free):

```java
try (RollScheduler scheduler = new RollScheduler(service, Duration.ofHours(4), Clock.systemUTC())) {
    scheduler.start();   // drains any backlog now, then runs every 4h
}
```

## CLI

`AschamArchiveCli` (`io.ascham.archive.cli`) drives the roll from the command line — one pass per
invocation, idempotent, so cron/systemd-timer scheduling is just "run it again". Reclamation and
cutover are the anticipated next subcommands.

```sh
./gradlew :ascham-archive:archive --args="roll --arena-dir /dev/shm/ito --dest build/warehouse"
./gradlew :ascham-archive:archive --args="roll --arena-dir /dev/shm/ito --dest http://localhost:8181/catalog"
./gradlew :ascham-archive:archive --args="roll --help"     # the full, documented option set
```

Every `ArchiveConfig` knob is an option — `--warehouse`, `--namespace`, `--sort table=col,col`,
`--catalog-property k=v`, `--max-segments-per-file`, `--target-file-size 512m`,
`--liveness-probe 5s` — plus `--arena-alert-bytes` for the arena-pressure ERROR log and `--quiet`.
S3 credentials come from `--s3-endpoint/--s3-key-id/--s3-secret` or their `ITO_S3_ENDPOINT` /
`ITO_S3_KEY_ID` / `ITO_S3_SECRET` env fallbacks (usually unnecessary: a REST catalog vends scoped
credentials). Exit codes: 0 success, 1 a table failed or the catalog could not be opened, 2 usage
error.

Anyone launching `java -cp` directly instead of the gradle task must pass the arena's JVM flags
(`--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`, see build.gradle.kts) or segment mapping fails at
startup.

## Reclamation (future utility)

The roll never deletes arena data — it only records, in every commit's snapshot summary, exactly
which segment files it archived. A standalone utility (not yet written) will consume that
provenance to unlink segments once the archive is durable. Two constraints it must honour:

- **Consume summaries before expiring snapshots.** Snapshot expiration deletes summaries with the
  snapshots; correctness does not care (the watermark is a table property), but the segment lists
  do. Reclaim first, expire after.
- **Orphan files are normal.** A run that dies between writing parquet and committing leaves
  unreferenced files in the warehouse. They are invisible to the table; standard Iceberg
  orphan-file removal collects them.

## Tests

```sh
./gradlew :ascham-archive:test      # the correctness suite — hermetic, includes full rolls to a local warehouse
./gradlew :ascham-archive:rollIT    # integration: roll through Lakekeeper/MinIO, read back via DuckDB
```

`rollIT` needs only the dev stack (no extension build):

```sh
docker compose -f ../dev/docker-compose.yml up -d
```

Each integration run uses its own catalog namespace, so runs are independent and repeatable. The
DuckDB read-back in `RestCatalogIT` is what pins "the query surface can read what the roll writes"
— including nanosecond timestamps (stored as unzoned `timestamp_ns`, UTC by convention, because
DuckDB has no zoned nanosecond type).
