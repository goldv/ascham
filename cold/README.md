# cold

The cold tier: rolls completed days out of the shared-memory arena into Parquet under an Apache
Iceberg catalog — the kdb rdb→hdb write-down. Design:
[`../docs/cold-tier-design-plan.md`](../docs/cold-tier-design-plan.md).

The roll is pure Java on the native Iceberg API. Segments are read through their zero-copy Arrow
roots (mmap, no copy), each file group is ordered by an index sort, rows stream through the Iceberg
parquet writer, and one transaction commits the whole day. DuckDB is no longer involved in writing —
it remains the query-side engine, and the dev stack verifies it can read everything the roll writes.

The destination is one string: a local path (or `file://`) rolls straight to disk through a
serverless Hadoop catalog — no services at all — while an `http(s)://` URI is an Iceberg REST
catalog (the dev stack's Lakekeeper), which owns the object storage underneath. A bare `s3://`
warehouse is rejected by design: Hadoop-catalog commits rename metadata files, which is not atomic
on object stores; S3 data goes through a REST catalog.

## Status

| Piece | State |
|---|---|
| `TableRoller` — the roll protocol: discover → freeze check → verify → roll ascending | **Implemented & tested** |
| `ArenaInventory` — pending days, the freeze check, I2 day-alignment verification | **Implemented & tested** |
| `IcebergRollExecutor` — native table create, group write, atomic day commit + watermark | **Implemented & tested** |
| `SegmentGroup` / `GroupSorter` — zero-copy group addressing, the index sort | **Implemented & tested** |
| `IcebergTypes` — arena → Iceberg types, with the widening conversions | **Implemented & tested** |
| `RollService` / `RollScheduler` — multi-table passes, pressure alert, scheduling + backoff | **Implemented & tested** |
| Reclamation utility (unlink archived segments from recorded provenance) | Not started — see below |
| Unified realtime + historical query surface | Not started (R6) |

## The protocol in one paragraph

A day is rollable only when no writer can still append to it: either a newer segment exists (the
normal case — the writer rotates at midnight even when idle), or the writer's heartbeat has stopped.
Before copying, every batch's zone map is checked to prove the day's rows really fall inside that UTC
day. The day's parquet files, its segment provenance, and the advanced watermark are committed in
**one atomic transaction**, so a partially-rolled day cannot exist — a crash mid-day commits nothing
and the next run simply rolls the day again. Days are rolled **oldest first and the run stops at the
first failure**, which is what lets a single watermark stand in for the whole set.

State lives in the table itself, in two places with two jobs:

| Where | What | Why it lives there |
|---|---|---|
| Table property `ito.rolled-through` | The watermark: highest fully-committed day | Correctness. Updated atomically with the data; survives snapshot expiration |
| Table property `ito.arena-dir` | Which arena owns this table | Verified on every open — two arenas must never share a table |
| Snapshot summary `ito.day` / `ito.segments` / `ito.arena-dir` / `ito.rows` | Which segment files fed each commit | Provenance for the reclaim utility (audit, not correctness) |

## File sizing

`segmentsPerFile` groups N consecutive same-day segments into one parquet file — the file-size
dial. At ~115 MB segments, `2` gives ~230 MB files; scale N toward the 128–512 MB (up to 1 GB for
very large tables) guidance. Each group is sorted by the table's sort columns (configured, or the
schema's own `arena.sort_key` declaration, or the time column) via an index sort: only the sort-key
columns are pulled onto the heap, the permutation is sorted, and rows stream out of the mmap in
order — memory is bounded by one group's keys, not its data. Created tables also declare
`write.target-file-size-bytes` and `write.parquet.compression-codec=zstd`.

## Usage

```java
ColdConfig config = ColdConfig.builder()
        .arenaBaseDir(Path.of("/dev/shm/ito"))
        .destination("/data/warehouse")            // or "http://localhost:8181/catalog"
        .segmentsPerFile(2)
        .build();                                  // sort order comes from arena.sort_key

try (RollExecutor executor = new IcebergRollExecutor(config)) {
    TableRoller.RollResult result = new TableRoller(config, executor).roll("quotes");
    // result.rolled() — days copied by this run; result.totalRows() — rows written
}
```

`TableRoller.cutoverDay(table)` returns the boundary the unified query surface splits on: historical
data covers everything before it, the arena serves everything from it onward.

For a whole deployment, `RollService` does every table in one pass and `RollScheduler` runs it:

```java
RollService service = new RollService(config, executor, 8L << 30);
try (RollScheduler scheduler = new RollScheduler(service, LocalTime.of(0, 15), Clock.systemUTC())) {
    scheduler.start();   // drains any backlog now, then runs daily at 00:15 UTC
}
```

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
./gradlew :cold:test      # the correctness suite — hermetic, includes full rolls to a local warehouse
./gradlew :cold:rollIT    # integration: roll through Lakekeeper/MinIO, read back via DuckDB
```

`rollIT` needs only the dev stack (no extension build):

```sh
docker compose -f ../dev/docker-compose.yml up -d
```

Each integration run uses its own catalog namespace, so runs are independent and repeatable. The
DuckDB read-back in `RestCatalogIT` is what pins "the query surface can read what the roll writes"
— including nanosecond timestamps (stored as unzoned `timestamp_ns`, UTC by convention, because
DuckDB has no zoned nanosecond type).
