# cold

The cold tier: rolls completed days out of the shared-memory arena into Parquet under an Apache
Iceberg catalog — the kdb rdb→hdb write-down. Design:
[`../docs/cold-tier-design-plan.md`](../docs/cold-tier-design-plan.md).

One embedded DuckDB does the whole move: it reads the arena through the native `arena` extension
(zero-copy over the mmap), sorts the day by `(sym, ts)`, encodes Parquet, and commits the Iceberg
snapshot. Java only orchestrates — discovery, safety checks, ordering, recovery.

## Status (R4–R5)

| Piece | State |
|---|---|
| `TableRoller` — the roll protocol: discover → freeze check → verify → roll ascending | **Implemented & tested** |
| `ArenaInventory` — pending days, the freeze check, I2 day-alignment verification | **Implemented & tested** |
| `SegmentReclaimer` — grace-gated unlink; the only thing that deletes arena data | **Implemented & tested** |
| `RollService` / `RollScheduler` — multi-table passes, pressure alert, scheduling + backoff | **Implemented & tested** |
| `DuckDbRollExecutor` — DDL, the atomic data+watermark commit, recovery queries | **Implemented & tested** |
| `TypeMapping` — arena → Iceberg types, with the widening casts | **Implemented & tested** |
| Unified realtime + historical query surface | Not started (R6) |

## The protocol in one paragraph

A day is rollable only when no writer can still append to it: either a newer segment exists (the
normal case — the writer rotates at midnight even when idle), or the writer's heartbeat has stopped.
Before copying, every batch's zone map is checked to prove the day's rows really fall inside that UTC
day. The copy and the watermark row are written in **one transaction**, so the log can never claim a
day the data does not have. Days are rolled **oldest first and the run stops at the first failure**,
which is what lets a single "highest rolled day" stand in for the whole set.

Rolling is idempotent — a run that dies part-way leaves nothing to reconcile, because the next run
re-derives what to do from the store itself:

| State found | Action |
|---|---|
| Day is in the roll log | Nothing; it is done |
| Data committed but no log entry (died mid-commit) | Repair the log — never re-copy, which would duplicate |
| Neither | Roll it |

## Usage

```java
ColdConfig config = ColdConfig.builder()
        .arenaBaseDir(Path.of("/dev/shm/ito"))
        .arenaExtension(Path.of("arena-duckdb/build/arena.duckdb_extension"))
        .catalog("http://localhost:8181/catalog", "ito")
        .sortColumns(Map.of("quotes", List.of("sym", "ts")))
        .build();

try (RollExecutor executor = new DuckDbRollExecutor(config)) {
    TableRoller.RollResult result = new TableRoller(config, executor).roll("quotes");
    // result.rolled() — days copied by this run; result.totalRows() — rows written
}
```

`TableRoller.cutoverDay(table)` returns the boundary the unified query surface splits on: historical
data covers everything before it, the arena serves everything from it onward.

For a whole deployment, `RollService` does every table in one pass and `RollScheduler` runs it:

```java
RollService service = new RollService(config, executor, Duration.ofMinutes(15), 8L << 30);
try (RollScheduler scheduler = new RollScheduler(service, LocalTime.of(0, 15), Clock.systemUTC())) {
    scheduler.start();   // drains any backlog now, then runs daily at 00:15 UTC
}
```

## Reclamation

Segments are released only after their rows are durably in the historical store, and only via
`SegmentReclaimer`. Three rules make that safe:

- **Only what the roll log names** — a segment is reclaimed because a committed archive row says it
  was copied, never because it merely looks old. This is why the roll names its inputs explicitly
  (`arena_scan([...])`) rather than re-listing the directory: the audit set and the reclaim set are
  the same list.
- **Only after grace** (default 15 min), measured against **the store's clock**, so a skewed roller
  clock cannot shorten its own safety margin. Grace must comfortably exceed readers' cutover-cache
  TTL, or a query could look for rows in an arena segment that just disappeared.
- **Never the newest segment**, which a live writer may be appending to.

Unlinking does not disturb in-flight readers: the kernel keeps an unlinked inode alive until the
last mapping is dropped, so a query that opened before the unlink runs to completion. It only stops
*new* readers.

## Tests

```sh
./gradlew :cold:test      # unit tests — no external services
./gradlew :cold:rollIT    # integration: real rolls into the local Iceberg catalog
```

`rollIT` needs the dev stack up and the arena extension built:

```sh
docker compose -f ../dev/docker-compose.yml up -d
DUCKDB=/path/to/duckdb ../arena-duckdb/scripts/build_extension.sh
```

Each integration test uses its own catalog namespace, so runs are independent and repeatable.
