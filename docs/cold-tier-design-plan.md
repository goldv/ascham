# Cold tier: EOD roll to Iceberg + unified realtime/historical access — design & implementation plan

The arena (M0–M5) holds live data in shared memory, and the `arena` DuckDB extension (D1–D5)
queries it with pushdown, zone-map pruning, and parallelism. Memory is finite: this document
designs the **cold tier** — the kdb-style EOD roll that moves each completed day from the arena
into **Parquet files under an Apache Iceberg catalog**, sorted by `(sym, ts)` for
kdb-parted-like symbol queries — and the **unified query surface** that lets one logical table
name (`quotes`) span realtime + historical data through the planned Flight SQL server
([flight-sql-design-plan.md](flight-sql-design-plan.md)).

The kdb analogy, made precise: the arena is the rdb, the Iceberg warehouse is the hdb, the roll is
the EOD write-down (`.u.end`), and `(sym, ts)` sorting inside day partitions plays the role of the
parted `sym` attribute — Parquet column min/max stats over sorted files give the same "seek to the
symbol" behaviour via Iceberg file/row-group pruning. Where kdb makes users query rdb and hdb
separately and splice by hand, §6 designs three alternatives for a single-name surface.

Facts verified up-front (July 2026): DuckDB's `iceberg` extension is installable for v1.5.5,
coexists with the unsigned arena extension in one session, and supports **full writes through an
attached Iceberg REST catalog** — `CREATE SCHEMA/TABLE`, `INSERT/UPDATE/DELETE/MERGE INTO`,
partition transforms (e.g. `day(ts)`), and Iceberg format-version 3 (which adds a nanosecond
timestamp type). The path-based `iceberg_scan(...)` remains read-only; writes require `ATTACH`.
Sources: duckdb.org iceberg "Writing to Iceberg" docs; "New DuckDB-Iceberg Features in v1.5.3"
(2026-05-29); "Writes in DuckDB-Iceberg" (2025-11-28).

## 1. Decisions

- **Storage**: Parquet under an **Iceberg REST catalog**. v1 dev stack is Lakekeeper + MinIO
  (docker-compose, §9); the interface is the standard REST catalog spec, so Polaris/Glue/S3-Tables
  are a config change later.
- **Roll trigger**: **EOD roll of prior day(s)**, kdb-style — pull-based, scheduled + on-startup +
  retry; not event-driven off rotation.
- **Roll writer**: the `:cold` Java service **embeds DuckDB (duckdb_jdbc)** and drives
  `INSERT INTO hist… SELECT … FROM arena_scan(…) ORDER BY sym, ts` — one engine does read (mmap,
  zero-copy) + external sort + Parquet encode + Iceberg commit, and dogfoods the arena extension
  daily. A pure-Java iceberg-core/parquet-mr writer is rejected for v1 (§5) but kept swappable.
- **Watermark**: an explicit **`roll_log` table in the catalog** — *not* `max(day)` over data
  partitions, which has two silent-gap failure modes (§3). The catalog is the only durable state;
  no sidecar files, no coordination service.
- **Correctness before dedup**: realtime/historical never overlap in query results by
  **predicate disjointness on `ts`** around a per-table cutover, so no `DISTINCT`/anti-join is ever
  needed and mid-roll queries are exact (§3, §6).
- **Sorting**: within each day partition, files are written `ORDER BY (sym, ts)` (per-table
  configurable; default `(ts)` when the table has no symbol column), with the sort order also
  declared in Iceberg table metadata.
- **Timestamps**: arena ns timestamps target **Iceberg V3 `timestamp_ns`**
  (`'format-version'='3'`); pinned contingency if the extension's V3 write path proves immature:
  documented cast to µs (never a shadow bigint column).
- **Retention hand-off**: the roller becomes the **sole owner of segment deletion**. The arena's
  count-based retention is disabled by default and demoted to an explicit opt-in emergency
  backstop (§8.1) — today it would happily delete un-archived segments.

## 2. Architecture

```
                    WRITE PATH                                    READ PATH
 producers ──► RotatingWriter (arena, Java)            Flight SQL server (Java)
                │  /dev/shm/ito/<table>/                 │ per-query DuckDB conn:
                │  <yyyyMMdd>.<seq>.arena                │   LOAD arena;  SET arena_dir
                │  (count-based retention OFF)           │   LOAD iceberg; ATTACH … AS hist
                ▼                                        │   CREATE VIEW <t> AS <union(cutover)>
         shared-memory segments ◄──────── mmap ──────────┤
                │                                        │      ad-hoc DuckDB CLI / Python
                │ frozen (not newest in dir /            │      (explicit rt + hist, §6C;
                │  stale heartbeat / older epoch)        │       v2: replacement-scan union, §6B)
                ▼                                        ▼
 :cold RollService (Java) ── DuckDB JDBC ──► INSERT INTO hist.ito.<t>   Iceberg REST catalog
   1. discover file-days < today                SELECT … ORDER BY sym, ts   (Lakekeeper, dev)
   2. verify frozen + day-aligned               FROM arena_scan([day files])     │
   3. roll ascending, abort on first failure                                     ▼
   4. append hist.ito_meta.roll_log  ── watermark ──► cutover_ts          MinIO / fs Parquet
   5. grace period, then unlink day's segments                            day(ts) partitions,
                                                                          files sorted (sym, ts)
```

One new Gradle module **`:cold`** (`settings.gradle.kts` already reserves the name). Its only new
dependency is `org.duckdb:duckdb_jdbc:1.5.5.0` (the same artifact the Flight plan selects) plus the
built `arena.duckdb_extension` binary — no parquet-mr, no iceberg-core, no hadoop.

## 3. Watermark and cutover — the correctness core

Every read-side construct in this design reduces to one union template per table:

```sql
SELECT <cols>              FROM hist.ito.<t>                    WHERE ts <  TIMESTAMP '<cutover>'
UNION ALL
SELECT <cols, with casts>  FROM arena_scan('<arena_dir>/<t>')   WHERE ts >= TIMESTAMP '<cutover>'
```

The two branches are **disjoint by construction on `ts`**: whichever side "owns" a day under the
current cutover serves it completely. The arena-side predicate is a single lower bound on the
zone-map time column — exactly what `arena_scan`'s pruning consumes, so rolled-but-not-yet-unlinked
segments prune to zero batches and the roll's grace window costs ~nothing.

### 3.1 Why "cutover = max day present in hist data" is wrong

The obvious watermark — derive cutover from the data itself — has two silent-gap failure modes:

1. **Out-of-order rolls.** If day D−2 fails to roll but D−1 succeeds, `max(day)` = D−1 ⇒ cutover =
   start(D). The hist side lacks D−2; the arena side filters it out (`ts >= start(D)`). D−2
   vanishes from results with no error.
2. **Event-time vs file-day skew.** Segment file-day is the *writer's wall clock* UTC date
   (`DailyRotationPolicy`); the cutover predicate and Iceberg partition are on the *event-time*
   `ts` column. One straggler row with `ts` past midnight inside a `20260729.*` segment lands in
   hist partition 2026-07-30 ⇒ `max(day)` jumps to 07-30 ⇒ cutover = start(07-31) ⇒ every
   genuinely-unrolled 07-30 arena row is excluded. One row triggers a whole-day gap.

### 3.2 The corrected protocol: three invariants + an explicit watermark table

- **I1 — ascending, gapless rolls.** The roller processes pending days strictly ascending and
  **aborts the run on the first failure** — day D is never rolled before every day < D. This makes
  "highest rolled day" a true watermark. Days with no segments (weekend, writer down) are
  vacuously rolled: the watermark advances past them at zero cost when the next data day rolls.
- **I2 — day-alignment verified, not assumed.** Ingest contract: every row in a file-day-X segment
  has `ts ∈ [X, X+1)` UTC. The roller *verifies* this from the batch zone maps before rolling
  (every batch sealed with `[ts_min, ts_max] ⊆ [D, D+1)` — requires the seal-on-close fix, §8.1).
  A violating segment aborts that table's run with an alert — never a silent split or truncation.
  Belt-and-braces: the roll `INSERT` still carries `WHERE ts >= D AND ts < D+1`, so even a
  verification bug cannot corrupt partition/watermark math.
- **I3 — unlink strictly after watermark commit, plus grace.** Day-D segments are unlinked only
  after the `roll_log` row for D is committed **and** a grace period has passed (default 15 min)
  that must exceed every reader's cutover-cache TTL (default 60 s) by a wide margin.

**The watermark is an explicit table** in the same warehouse:

```sql
CREATE SCHEMA IF NOT EXISTS hist.ito_meta;
CREATE TABLE IF NOT EXISTS hist.ito_meta.roll_log (
    table_name   VARCHAR,
    day          DATE,
    rows         BIGINT,
    segments     VARCHAR,      -- comma-joined file names rolled, for audit == reclaim set
    committed_at TIMESTAMP
);

-- cutover for table t (UTC midnight after the highest rolled day):
SELECT coalesce(max(day), DATE '1970-01-01') + 1 FROM hist.ito_meta.roll_log WHERE table_name = ?;
```

A tiny table: one catalog metadata fetch + one small Parquet read — cheap enough to poll every
60 s per reader process.

### 3.3 Why the data-commit → log-commit gap is safe

The roller commits day D's data to `hist.ito.<t>` first, then appends the `roll_log` row (in one
transaction if the extension supports the multi-table commit — R1 pins this; correctness does not
depend on it). A crash between the two leaves cutover at start(D): queries serve D **complete from
the arena side** (`ts >= cutover`, and D's segments still exist because unlink is log-gated, I3),
while hist's extra copy of D is invisible (`ts < cutover`). No dupes, no gaps. A cached cutover is
only ever stale-*low*, which is the safe direction, provided TTL ≪ unlink grace (I3). In-flight
queries that bound before an unlink are additionally protected by the kernel inode refcount
(mapped segments stay readable after unlink — the arena's M5 reclamation semantics).

Recovery is idempotent, three branches per (table, day):

1. `(t, D)` in `roll_log` → already rolled → unlink only (if files remain).
2. else if `EXISTS (SELECT 1 FROM hist.ito.<t> WHERE ts >= D AND ts < D+1)` (partition-pruned,
   cheap at EOD frequency) → data committed, log row lost in a crash → append the log row only.
   One `INSERT` = one Iceberg snapshot, so presence of any row implies the full day.
3. else → roll normally.

## 4. The roll pipeline (`io.ito.cold.RollService`)

Pull-based, single-threaded per table (tables serial in v1). Triggers: cron-style daily at
`roll.time` (default 00:15 UTC); on service startup (backlog catch-up); a retry loop with
exponential backoff (30 s → 15 min cap) while any table has pending days and the last attempt
failed (e.g. catalog down at EOD). "EOD" is never inferred from rotation events — the roller just
asks *which file-days < today exist and are frozen*.

Per run, per table `<base>/<t>`:

1. **Discover.** List segments (`^\d{8}\.\d+\.arena$`, the same rule as C++ `list_segments` and
   Java `SegmentDirectory.list()`); group by file-day; `pending = { days < today(UTC) }`, sorted
   ascending.
2. **Freeze check per day D.** Every segment of D must be provably frozen: it is **not the newest
   segment in the directory** (writers only append to the newest — the strongest signal), or it is
   the newest but the writer is gone — heartbeat stale beyond the liveness threshold
   (`LivenessMonitor` rule) or `SegmentDirectory.readEpoch(seg) < latestEpoch()`. The
   idle-but-live writer case (no append since midnight, so `DailyRotationPolicy` never fired) is
   closed by the rotate-on-heartbeat fix (§8.1); until then, skip and retry.
3. **Ensure hist table.** `CREATE TABLE IF NOT EXISTS hist.ito.<t> (…) PARTITIONED BY (day(ts))`
   with `'format-version'='3'` and the schema mapping of §7. Exact DuckDB DDL for the partition
   transform, sort order, and table properties is pinned in R1; the designed fallback is a
   one-time table creation against the REST catalog directly (plain HTTPS from Java or pyiceberg)
   with DuckDB doing only the `INSERT`s.
4. **Verify day-alignment (I2)** from the Java-side catalog (`SnapshotReader` batch stats — no
   DuckDB needed): all batches sealed, all `[ts_min, ts_max] ⊆ [D, D+1)`. Violation → alert +
   abort this table's run.
5. **Roll, ascending, abort-on-failure (I1):**

```sql
BEGIN;
INSERT INTO hist.ito.quotes
  SELECT sym, ts, CAST(size AS BIGINT) AS size, …    -- explicit list; unsigned widening (§7)
  FROM arena_scan(['/dev/shm/ito/quotes/20260729.0.arena',
                   '/dev/shm/ito/quotes/20260729.1.arena'])          -- LIST overload (§8.2)
  WHERE ts >= TIMESTAMP '2026-07-29' AND ts < TIMESTAMP '2026-07-30' -- belt-and-braces (I2)
  ORDER BY sym, ts;
INSERT INTO hist.ito_meta.roll_log
  VALUES ('quotes', DATE '2026-07-29', <rows>, '20260729.0.arena,20260729.1.arena', now());
COMMIT;
```

   Multi-seq days are handled naturally — all of D's files go in the list and `ORDER BY` produces
   one globally sorted day. The sort is DuckDB's external sort: `:cold` sets `memory_limit` and
   `temp_directory`, so a larger-than-memory day spills rather than OOMs.
6. **Unlink (I3).** After the grace period: `SegmentDirectory.unlink(path)` for exactly the files
   recorded in the `roll_log` row — the audit set *is* the reclaim set (no re-listing between roll
   and unlink, hence the LIST overload in §8.2). Crash between log and unlink → recovery branch 1.
7. **Backlog.** Writer down for days → multiple pending days roll ascending in one run. Catalog
   down for days → pending days accumulate in shm (alert when arena bytes cross a threshold); the
   retry loop drains oldest-first when it returns. `/dev/shm` must be sized for ≥ 2 days + margin
   (today + yesterday-until-rolled + grace).

## 5. Roll writer choice

**Recommendation: (a) `:cold` embeds DuckDB** — Java purely orchestrates (discovery, freeze checks,
verification, scheduling, retries, unlink); DuckDB moves the bytes.

| | (a) DuckDB-embedded | (b) Java iceberg-core + parquet-mr |
|---|---|---|
| New Java deps | duckdb_jdbc only | iceberg-core, parquet-mr, hadoop shims (large, CVE-prone) |
| Write path | columnar end-to-end (mmap → vectors → Parquet) | `BatchView.root()` → row-oriented parquet-mr |
| Full-day sort | DuckDB external sort (spills) | hand-rolled / Arrow external sort — significant new code |
| Commit | extension's Iceberg snapshot commit | full control (metrics, file sizes, properties in-txn) |
| Dogfooding | exercises arena_scan pushdown daily | none |
| Control gaps | file sizing / sort-order metadata via table properties or one-off REST calls | none |

The control (b) buys is recoverable in (a) via Iceberg table properties
(`write.target-file-size-bytes`, `write.parquet.compression-codec`) plus, where DuckDB DDL can't
express something (sort-order metadata at create time), a one-time REST call — that hybrid is
already the design: Java can speak plain HTTPS to Lakekeeper for the few metadata operations
DuckDB lacks. The roll protocol (§4) is writer-agnostic; `RollExecutor` is an interface so a
future (b) implementation swaps in without touching the coordination logic.

## 6. Unified query surface — three alternatives

All three share the union template and cutover semantics of §3. They differ in *who builds the
union and where it works*.

**A — Flight-server per-connection views.** The server's per-query connection setup (which per the
Flight plan's D6 note already becomes `LOAD arena; SET arena_dir`) additionally runs `LOAD iceberg;
CREATE SECRET …; ATTACH … AS hist;` and `CREATE VIEW <t> AS <union(cutover)>` for each served
table. Cutover comes from a server-singleton **`CutoverTracker`**: a background thread polls
`roll_log` (TTL 60 s, force-refresh hook pluggable later); per-query connections read the cached
value — zero per-query catalog round-trips. Per-connection `ATTACH` cost (auth + metadata fetch) is
mitigated by a small pool of pre-attached connections, which the Flight plan already contemplates.

**B — the arena extension builds the union.** `ArenaReplacement` currently returns a
`TableFunctionRef`; verified in DuckDB v1.5.5 source (`Binder::BindWithReplacementScan`) that a
replacement scan may return **any `TableRef`** — the binder auto-wraps it in `SELECT * FROM <ref>`
— so it can return a `SubqueryRef` holding a `SetOperationNode` (UNION ALL) with the hist branch as
a `BaseTableRef` into the attached catalog and the arena branch as today's function ref, each under
a `ts` comparison against the cutover. Filter pushdown flows through UNION ALL in the optimizer.
New settings: `arena_hist_catalog` (e.g. `hist.ito`), `arena_hist_refresh_ms` (cutover cache TTL).
Works in **every** DuckDB host — CLI, Python, JDBC, and the Flight server itself. Costs: C++
parse-tree construction, a runtime coupling (the arena extension querying the attached Iceberg
catalog during name replacement), and a muddier error surface when the catalog is detached.

**C — explicit kdb-style names.** `quotes` stays realtime-only (today's replacement scan);
`hist.ito.quotes` via `ATTACH`; users union manually. Ship documented init SQL
(`dev/hist-attach.sql` + a per-table `CREATE VIEW` snippet — DuckDB macros can't parameterize
identifiers, so view text is per-table, optionally generated). Zero code.

| | A (server views) | B (extension union) | C (explicit) |
|---|---|---|---|
| Works in CLI/Python directly | no | yes | yes |
| Single-name UX | yes (via server) | yes | no |
| Implementation | Java only, small | C++, moderate | docs only |
| Coupling | server ↔ catalog | arena ext ↔ iceberg ext + catalog | none |
| Cutover freshness control | server-owned, easy | per-process TTL setting | user's problem |
| Failure isolation | catalog down ⇒ view creation fails, clear error | replacement-time failures muddy "table not found" | explicit |

**Recommendation: A + C for v1, B as v2.** A needs zero C++ and the Flight server is the product's
front door; C costs nothing and keeps ad-hoc CLI/Python users unblocked. B then *subsumes* A —
the server's connection setup shrinks to `LOAD + SET + ATTACH` and the same bare `quotes` works
identically in ad-hoc sessions; A's `CutoverTracker` logic ports conceptually straight into the
extension's cached lookup. A → B is additive, not a migration.

## 7. Schema mapping (arena → DuckDB scan surface → Iceberg)

| arena | DuckDB (scan, exists today) | Iceberg (write) | Notes |
|---|---|---|---|
| BOOL | BOOLEAN | boolean | |
| INT8 / INT16 / INT32 / INT64 | TINYINT…BIGINT | int / int / int / long | narrow ints widen to int |
| UINT8 / UINT16 | UTINYINT / USMALLINT | int | explicit `CAST(x AS INTEGER)` in roll + union |
| UINT32 | UINTEGER | long | `CAST(… AS BIGINT)` |
| UINT64 | UBIGINT | decimal(20,0) | `CAST(… AS DECIMAL(20,0))`; documented; avoid u64 in new tables |
| FLOAT32 / FLOAT64 | FLOAT / DOUBLE | float / double | |
| DECIMAL128(p,s) | DECIMAL(p,s) | decimal(p,s) | p ≤ 38 |
| DATE32 | DATE | date | |
| TIME64_NS | TIME (µs) | time (µs) | ns already truncated at the scan surface — hist matches live, no *new* loss |
| TIMESTAMP (ns) | TIMESTAMP_NS | **timestamp_ns (V3)** | `'format-version'='3'`; R1 round-trips nanos; contingency = documented µs cast |
| TIMESTAMP (µs, ±tz) | TIMESTAMP / TIMESTAMP_TZ | timestamp / timestamptz | |
| FIXED_SIZE_BINARY | BLOB | binary | width not preserved (DuckDB already erases it) |
| UTF8 | VARCHAR | string | |
| BINARY | BLOB | binary | |

The explicit column lists (with the unsigned casts) appear in **both** the roll `INSERT` and the
arena branch of the union so the two union branches type-match positionally.

**Table shape:** partition spec `day(ts)` where `ts` = the table's `arena.time_column`; sort order
`(sym, ts)`, per-table configurable in `:cold` config (default `(ts)` when there is no symbol
column), declared as Iceberg sort-order metadata at creation *and* enforced by `ORDER BY` on every
roll `INSERT`. Properties: `write.target-file-size-bytes = 536870912`,
`write.parquet.compression-codec = zstd`; large row groups + sorted `sym` give Parquet column
min/max stats the shape needed for kdb-parted-like symbol skipping through the iceberg extension's
file/row-group pruning. "Day" is UTC throughout, matching `RotatingWriter`'s
`DailyRotationPolicy`. One caveat carried from the extension: zone-map pruning requires the time
column to be a *timestamp* type (TIME64/DATE32 constants would compare in the wrong unit).

## 8. Changes to existing components (each small)

### 8.1 arena (Java)

- **Seal the final batch on close.** `SegmentWriter.close()` seals the in-progress batch (if it
  has rows) before unmapping; `RotatingWriter.rotate()` inherits it via `current.close()`. Today
  the last batch of every rotated segment keeps `IN_PROGRESS_BIT` forever with unpublished stats —
  which breaks I2's zone-map verification and blinds `arena_scan` pruning for that batch. Tests:
  `arena_segments()` on a closed segment shows every batch sealed with published stats.
- **Gate retention.** `RotatingWriter.applyRetention()`'s count-based unlink is the design's
  biggest live hazard — it deletes oldest-first with no knowledge of what has been archived.
  Change: retention **off by default**; count-based eviction becomes an explicit opt-in emergency
  backstop that logs at ERROR when it fires. The roller is the sole normal-path unlink owner.
- **Rotate on heartbeat.** `RotatingWriter.heartbeat()` also consults the `RotationPolicy`, so an
  idle-but-live writer still rotates shortly after midnight and yesterday's segment freezes
  promptly (otherwise the roller waits on heartbeat staleness for quiet tables).

### 8.2 arena-duckdb (C++)

- **`arena_scan(LIST(VARCHAR))` overload** (~30 lines in `arena_scan.cpp`): a second registered
  signature taking `LogicalType::LIST(LogicalType::VARCHAR)`; bind iterates the list values
  instead of `ResolveSegmentPaths`. Why a LIST rather than a `day :=` named parameter: the roller
  must unlink **exactly the files it rolled**; a day filter re-lists the directory at bind time,
  opening a discover/roll/unlink TOCTOU seam, whereas an explicit list makes
  rolled-set == logged-set == unlinked-set by construction. (Also generically useful.)
- **(v2, option B only)** the union replacement scan of §6B, with `arena_hist_catalog` /
  `arena_hist_refresh_ms` settings and the cached cutover lookup.

### 8.3 flight-sql-design-plan.md (doc amendment; built in that plan's milestones)

- Fold the D6 note into the base design: per-connection setup = `LOAD arena; SET arena_dir;
  LOAD iceberg; CREATE SECRET; ATTACH hist;` + per-table union views (replacing
  `registerArrowStream` for arena tables and restoring pushdown).
- Add `CutoverTracker` (background `roll_log` poll, TTL 60 s, cached per table) + the pre-attached
  connection pool.
- Document the cross-plan invariant: **cutover-cache TTL ≪ roller unlink grace** (60 s vs ≥ 15 min).

### 8.4 `:cold` (new Gradle module)

`io.ito.cold`: `RollService` (scheduling/retry), `TableRoller` (the §4 protocol), `RollExecutor`
(interface; DuckDB-JDBC impl), `CutoverQueries` (the `roll_log` SQL, shared with the Flight
server), `ColdConfig` (arena base dir, catalog endpoint/credentials, per-table sort columns,
`roll.time`, grace, backstop retention). Uses arena's Java classes (`SegmentDirectory`,
`SnapshotReader` for I2 verification), so it carries the same `--add-opens` JVM flags.

## 9. Local dev stack

New `dev/docker-compose.yml`: **Lakekeeper** (+ its Postgres) + **MinIO** (+ an `mc` bootstrap job
creating bucket `ito-warehouse`), Lakekeeper bootstrapped with one warehouse `ito` backed by
`s3://ito-warehouse` (path-style, `http://minio:9000`). Dev auth: Lakekeeper's no-auth dev mode
for v1; OAuth2 secret wiring documented for later (the DuckDB side is a one-line `CREATE SECRET`
swap). Client smoke script `dev/hist-attach.sql`:

```sql
INSTALL iceberg; LOAD iceberg;
CREATE SECRET minio (TYPE S3, KEY_ID 'minio', SECRET 'minio123',
                     ENDPOINT 'localhost:9000', URL_STYLE 'path', USE_SSL false);
ATTACH 'ito' AS hist (TYPE iceberg, ENDPOINT 'http://localhost:8181/catalog');
```

## 10. Testing

- **R1 spike script** (`dev/` shell + SQL): the de-risk round-trip, run before any Java exists.
- **`:cold` integration tests** (JUnit, @TempDir arena + docker-compose catalog via Testcontainers
  or a compose precondition): write days with `RotatingWriter`, roll, assert hist row parity +
  file sortedness + `roll_log` contents; failure injection for all three recovery branches
  (§3.3), including kill-between-commits convergence with a dupe-detector query
  (`GROUP BY … HAVING count(*) > 1`).
- **Mid-roll correctness** (the design's signature test): a client issues the union query in a
  loop while the roller runs; every response must have exactly the expected row count — no dupes,
  no gaps — across the data-commit, log-commit, and unlink transitions.
- **Extension tests**: LIST-overload sqllogic-style checks in `arena-duckdb/scripts/test_extension.sh`.

## 11. Milestones

| # | Deliverable | Exit tests |
|---|---|---|
| **R1** | Dev stack + write-path spike (de-risk, no Java): compose up; from DuckDB CLI, create V3 day-partitioned table, `INSERT … FROM arena_scan(golden segment) ORDER BY sym, ts`, read back | ns timestamps round-trip (or µs contingency recorded); partition/sort DDL syntax pinned (or REST-create fallback pinned); data+log single-txn atomicity answer recorded |
| **R2** | Arena hardening: seal-on-close, retention gated off + ERROR backstop, rotate-on-heartbeat | `arena_segments()` shows closed segment fully sealed; backstop-fires-ERROR test; idle-writer rotates after midnight test |
| **R3** | `arena_scan(LIST)` overload | list-scan result == dir-scan filtered to those files; zone-map pruning intact per file |
| **R4** | `:cold` core roll (single table; §4 protocol; all three recovery branches) | 2-day round-trip: row parity, sortedness, roll_log; kill -9 between data and log commits → rerun converges, zero dupes; larger-than-memory-limit day sorts via spill |
| **R5** | Unlink + backlog + multi-table | grace-gated unlink (files gone only after grace); multi-seq day; 3-day writer-down backlog drains ascending; catalog-down retry + shm-pressure alert; ascending-abort (D−2 fails ⇒ D−1 not attempted) |
| **R6** | Unified surface v1: Flight-server views (A) + CutoverTracker; explicit-path docs (C) | continuous union query across a live roll: no dupes/no gaps at every transition; `EXPLAIN ANALYZE` shows arena pruning of rolled-not-unlinked segments; TTL ≪ grace asserted in config validation |
| **R7** (v2, optional) | Extension union replacement scan (B) | bare `quotes` in a plain DuckDB CLI session (LOAD arena + ATTACH) resolves to the union; the R6 mid-roll test passes from the CLI |

## 12. Risks

1. **Iceberg-extension write maturity** (V3 ns writes, partition/sort DDL, multi-table txn
   atomicity) — front-loaded into the R1 spike with pinned contingencies: µs cast; REST-create
   fallback; recovery branches make non-atomic commits safe.
2. **Un-rolled data deleted by legacy retention** — eliminated structurally in R2 (off by default,
   single unlink owner); backstop alarms if it ever fires.
3. **Stale cutover after unlink** → transient gap — prevented by the TTL ≪ grace invariant
   (documented in both plans, asserted in R6); in-flight queries additionally protected by inode
   refcounting.
4. **Day-alignment violations** (late/skewed event time) — verified per roll from zone maps;
   abort + alert, never silent; the belt-and-braces `ts` predicate keeps partitions exact even
   under a verification bug.
5. **Full-day sort memory** — DuckDB external sort bounded by `memory_limit` + `temp_directory`
   from `:cold` config; R4 includes a larger-than-memory day.
6. **/dev/shm exhaustion during multi-day catalog outage** — sizing guidance (≥ 2 days + margin),
   bytes-threshold alert, ascending retry drains oldest first.
7. **Catalog down at query time (option A)** — view creation fails with a clear error; a
   realtime-only fallback view is a possible follow-up but deliberately not default (silent
   partial results are worse than errors).

## 13. Open questions

1. Exact DuckDB 1.5.5 iceberg DDL for partitioned/sorted table creation and settable properties —
   R1 pins it; REST-create fallback already designed.
2. Is the data + roll_log two-INSERT transaction a single atomic REST multi-table commit through
   the extension? — R1; either answer is safe (§3.3).
3. `CutoverTracker` push-refresh from the roller vs TTL-only — TTL-only in v1; a refresh hook is
   trivial to add later.
4. Where per-table sort columns live — `:cold` config in v1; an `arena.sort_columns` schema
   metadata key (beside `arena.time_column`) is the cleaner long-term home.
