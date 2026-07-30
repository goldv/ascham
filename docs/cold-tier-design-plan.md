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
  configurable; default `(ts)` when the table has no symbol column). Declaring that sort order in
  Iceberg *metadata* is **not** possible through DuckDB — R1 found `ALTER TABLE … SET SORTED BY`
  parses but returns "Not implemented" — so v1 gets physical sortedness from the roll's explicit
  `ORDER BY` (which is what delivers the pruning benefit), and declaring the metadata is deferred to
  the REST-create path (§10, R4). Worth knowing for later: DuckDB *does* honour a declared sort
  order on `INSERT` when one exists (hence its `unsafe_iceberg_ignore_sort_order` escape hatch), so
  declaring it later makes sorting automatic rather than by-convention.
- **Timestamps**: arena ns timestamps map to **Iceberg V3 `timestamp_ns`** — **verified end-to-end
  at R1** (§10): `2026-07-30 13:54:22.689010141` round-trips exactly, and min/max ns bounds match
  the arena source bit-for-bit. The µs-cast contingency is **not needed** and is dropped.
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
    arena_dir    VARCHAR,      -- which arena those names belong to (see below)
    committed_at TIMESTAMP
);

-- cutover for table t (UTC midnight after the highest rolled day):
SELECT coalesce(max(day), DATE '1970-01-01') + 1 FROM hist.ito_meta.roll_log WHERE table_name = ?;
```

A tiny table: one catalog metadata fetch + one small Parquet read — cheap enough to poll every
60 s per reader process.

### 3.3 Why the data-commit → log-commit gap is safe

The roller commits day D's data to `hist.ito.<t>` and appends the `roll_log` row in **one
transaction**. R1 verified this is a genuine atomic multi-table commit, not two sequential ones: a
`BEGIN; INSERT data; INSERT log; COMMIT;` produces a single `POST /catalog/v1/<wh>/transactions/commit`
carrying commit actions for both tables (confirmed in the catalog's audit log), and `ROLLBACK`
leaves both tables empty. So the split-state window below should never occur in practice against a
catalog that implements multi-table commit.

The recovery logic is kept anyway, as defence in depth: it is what makes the roll safe if the two
INSERTs ever run outside a transaction, against a catalog without multi-table commit, or if the
process dies mid-COMMIT. A crash between the two leaves cutover at start(D): queries serve D **complete from
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
3. **Ensure hist table**, using the DDL pinned by R1 (§10) — two statements, not one:

```sql
CREATE TABLE IF NOT EXISTS hist.ito.quotes (sym VARCHAR, ts TIMESTAMP_NS, px BIGINT)
    WITH ('format-version' = '3');          -- quoted key; see the two traps below
ALTER TABLE hist.ito.quotes SET PARTITIONED BY (day(ts));
```

   Two traps, both found the hard way at R1 and both silent-ish:
   - `PARTITIONED BY` inside `CREATE TABLE` is a **parser error** — partitioning is only reachable
     via the follow-up `ALTER TABLE … SET PARTITIONED BY`, which does correctly store a `day`
     transform and set `default-spec-id`.
   - The property key must be **quoted** (`'format-version' = '3'`). The unquoted-identifier form
     `format_version = 3` is accepted by the parser, silently ignored, and the table is created as
     **v2** — which then rejects `timestamp_ns` with *"not supported until v3 but format version is
     v2"*. Failing at table-creation time is the good case; the bad case is not noticing.

   Declared sort-order metadata still needs the REST fallback (one-time `CREATE TABLE` against the
   catalog's HTTP API from Java, or pyiceberg, with DuckDB doing only the `INSERT`s) — see §1.
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

**Status: implemented (R2).** All three landed; 85 arena tests green, and the C++ reader +
extension suites confirm the golden corpus is byte-unchanged.

- **Seal the final batch when the writer is finished with a segment.** New
  `SegmentWriter.sealFinal()`, called by `RotatingWriter` on both lifecycle exits — `rotate()` and
  `close()`. Previously the last batch of every rotated-away segment kept `IN_PROGRESS_BIT` forever
  with unpublished (zeroed) stats, which breaks I2's day-alignment verification and blinds
  `arena_scan` pruning for that batch. Measured after the change: a live writer's trailing partial
  batch now reports `sealed=true` with real stats, and a time-filtered scan prunes **7 of 31**
  batches including the trailing one.

  *Deliberately not folded into `SegmentWriter.close()`*, which was the obvious first move: a
  segment whose last batch stays in progress forever is exactly the signature of a writer that
  **died**, and that state must stay reachable and readable — the golden corpus pins it, and the
  C++ conformance suite asserts on it. Putting the seal in the low-level `close()` would have
  rewritten a cross-language contract that isn't actually changing. So `close()` stays a pure
  resource release and the *lifecycle* owner decides when a segment is finished. An empty trailing
  batch is left in progress either way (sealing it would publish a meaningless `[0,0]` range, and
  zero-row batches are already skipped everywhere).
- **Gate retention.** `RotatingWriter`'s count-based unlink was the design's biggest live hazard —
  it deletes oldest-first knowing nothing about what has been archived. Now expressed as a
  `Retention` value: **`Retention.none()` is the default**, and `Retention.emergencyBackstop(n)` is
  the explicit opt-in that logs at ERROR on every eviction (in an archived deployment, it firing at
  all means unarchived data is being dropped). The roller is the sole normal-path unlink owner.
- **Rotate on heartbeat.** `RotatingWriter.heartbeat()` now evaluates the `RotationPolicy` first, so
  an idle-but-live writer still rotates shortly after midnight and yesterday's segment freezes
  promptly. Without it, rotation is only checked inside `append`, so a quiet table would pin
  yesterday's segment open — unarchivable, because the roller must never touch a segment a live
  writer may still append to — until the next row happened to arrive.

### 8.2 arena-duckdb (C++)

**Status: implemented (R3).**

- **`arena_scan(LIST(VARCHAR))` overload.** `arena_scan` is now a `TableFunctionSet` with two
  signatures — `arena_scan(VARCHAR)` and `arena_scan(LIST(VARCHAR))` — sharing one bind. Each list
  element resolves by the same rule as the scalar form (a segment file, or a directory expanded
  oldest-first), so naming files scans exactly those files in that order, with projection pushdown
  and zone-map pruning unchanged. Why a LIST rather than a `day :=` named parameter: the roller must
  unlink **exactly the files it rolled**; a day filter re-lists the directory at bind time, opening
  a discover/roll/unlink TOCTOU seam, whereas an explicit list makes
  rolled-set == logged-set == unlinked-set by construction.

  **Duplicates are rejected, not deduplicated** — scanning a file twice would silently double-count
  its rows into the historical store, and quietly dropping the repeat would hide the caller's bug
  just as effectively. Empty lists, NULL entries, and a NULL argument are hard errors too.
  `dev/r1-spike.sh` now rolls through the list form, including a multi-seq day (2 segments → 3,114
  rows, both file names recorded in `roll_log.segments`).
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

**Status: implemented (R4 core, R5 reclamation + orchestration).** As built, `io.ito.cold`:

| Class | Role |
|---|---|
| `TableRoller` | The §4 protocol — discover, freeze-check, verify, roll ascending, recover. Owns I1 and drives I2 |
| `ArenaInventory` | Arena-side reads: pending days, the freeze check, I2 day-alignment verification. Never deletes |
| `SegmentReclaimer` | The only component that deletes arena data. Owns I3: roll-log-named segments only, past grace, never the newest |
| `RollService` | One pass over every table — roll then reclaim — plus the arena-pressure alert. Tables are isolated from each other's failures |
| `RollScheduler` | Startup drain, daily cadence, exponential backoff while anything still fails |
| `RollExecutor` / `DuckDbRollExecutor` | The historical store, behind an interface so the engine stays swappable (§5) |
| `TypeMapping` | Arena → Iceberg types (§7): the DDL column list and the matching SELECT with widening casts |
| `ColdConfig` | Arena base dir, catalog coordinates, per-table sort columns, memory limit / temp dir, liveness probe |

Only new dependency: `org.duckdb:duckdb_jdbc` (`:cold` also depends on `:arena` for
`SegmentDirectory`/`SnapshotReader`, so it carries the same `--add-opens` JVM flags).

**Two implementation notes that changed the design's shape.**

The heartbeat is a *counter*, not a timestamp, so a single read cannot distinguish a dead writer
from a quiet one — liveness needs two samples separated in time. `ArenaInventory.isFrozen`
therefore treats segment *ordering* as the primary signal (a newer segment exists ⇒ this day is
finished, decided with no waiting at all) and only falls back to a timed heartbeat probe for the
case where a past day still owns the newest segment, which after R2's rotate-on-heartbeat means the
writer probably died.

The grace window is evaluated **in the store**, as `committed_at <= now() - INTERVAL n SECOND`
against the catalog's own clock, rather than by comparing a fetched timestamp locally. A roller
whose clock runs fast would otherwise shorten its own safety margin and could unlink a day that
readers are still serving from the arena — the one failure mode grace exists to prevent.

**One implementation note that changed the design's shape.** The heartbeat is a *counter*, not a
timestamp, so a single read cannot distinguish a dead writer from a quiet one — liveness needs two
samples separated in time. `ArenaInventory.isFrozen` therefore treats segment *ordering* as the
primary signal (a newer segment exists ⇒ this day is finished, decided with no waiting at all) and
only falls back to a timed heartbeat probe for the case where a past day still owns the newest
segment, which after R2's rotate-on-heartbeat means the writer probably died.

## 9. Local dev stack

**As built** (`dev/docker-compose.yml`, compose project `ito-cold-dev`): **Lakekeeper** 0.13.1
(+ private Postgres 17, not published) + **MinIO** (+ an `mc` job creating bucket `ito-warehouse`),
with `dev/catalog-init.sh` bootstrapping the server and creating warehouse `ito` over
`s3://ito-warehouse`. Both one-shot jobs are idempotent, so `up` is re-runnable. Host ports:
**8181** catalog, **9100/9101** MinIO API/console — deliberately not MinIO's default 9000, which
collides with other local stacks. Dev auth is Lakekeeper's no-auth mode; OAuth2 is a
`CREATE SECRET` swap later.

Client attach recipe: **`dev/hist-attach.sql`**. Four clauses in it are load-bearing, each of which
cost a debugging round at R1 — worth reading before changing it:

- `LOAD httpfs` **before** creating the S3 secret: the `S3` secret type lives in httpfs, not iceberg.
- `AUTHORIZATION_TYPE 'none'` on `ATTACH`: it defaults to `oauth2` and otherwise fails with
  *"AUTHORIZATION_TYPE is 'oauth2', yet no 'secret' was provided"*.
- **The warehouse's S3 endpoint must be reachable from both the host and the containers.** An
  Iceberg REST catalog *vends* its storage endpoint to clients in the LoadTable response, and DuckDB
  uses that for data-file I/O — so the natural `http://minio:9000` makes every host-client write die
  with *"Could not resolve hostname"*. The dev stack uses the docker0 gateway
  (`http://172.17.0.1:9100`), which is a host interface **and** container-reachable, so one value
  works for both. Override with `ITO_S3_ENDPOINT`.
- **Credential vending must be ON** (`sts-enabled: true` in the warehouse storage profile). This is
  not a preference: the catalog always vends a storage config scoped to the table's prefix
  (`s3://bucket/warehouse/<table-uuid>`), and DuckDB selects secrets by longest-matching scope — so
  the vended entry always beats a client's own bucket-level `SECRET`. With vending disabled that
  entry carries no keys, every data-file request goes out anonymous, and MinIO answers
  **403 AccessDenied**. Enabling vending also means clients need no MinIO keys of their own.

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
| **R1** ✅ **done** | Dev stack (`dev/docker-compose.yml`, `dev/catalog-init.sh`, `dev/hist-attach.sql`) + write-path spike `dev/r1-spike.sh`: rolls a real arena segment into a V3 day-partitioned Iceberg table in one transaction with its `roll_log` row, and asserts the result | **All green** — see §10 for the answers. 2,339 rows, arena↔iceberg row parity, ns bounds exact (`…22.689010141`, 2,335 rows carrying sub-µs digits on both sides), format-version 3 + `day` transform stored, partition pruning reads 1 file. Re-runnable as a regression test |
| **R2** ✅ **done** | Arena hardening: seal-when-finished (`SegmentWriter.sealFinal()` driven by `RotatingWriter`), `Retention.none()` by default + ERROR-logging backstop, rotate-on-heartbeat | **All green** — `SealOnCloseTest` (5), `RetentionPolicyTest` (4), `IdleRotationTest` (2); 85 arena tests total. Cross-language unchanged: C++ reader 15/15 and the extension SQL suite still pass against the same golden corpus. Live-writer check: trailing partial batch now `sealed=true` with published stats, and pruning covers it (kept 7 of 31 batches) |
| **R3** ✅ **done** | `arena_scan(LIST)` overload (`TableFunctionSet`, two signatures, one bind) | **All green** — 10 new assertions in `scripts/test_extension.sh` (31 total): list == dir scan when it names every segment, single element, pushdown and zone-map pruning intact through the list, directory element expands, and duplicate / empty-list / NULL-entry / NULL-argument all rejected with clear errors |
| **R4** ✅ **done** | `:cold` module: `TableRoller` (§4 protocol, all three recovery branches), `ArenaInventory` (discovery, freeze check, I2 verification), `DuckDbRollExecutor`, `TypeMapping`, `ColdConfig` | **All green** — 16 unit tests + 8 catalog integration tests (`./gradlew :cold:rollIT`): 2-day round-trip with row parity, per-partition (sym, ts) sortedness, watermark and `roll_log.segments` audit; ns fidelity preserved; re-run is a no-op with zero duplicates; **data-committed-but-unlogged repairs the log instead of re-copying**; a live writer's day is left alone; a misaligned day aborts whole; a 150k-row day sorts by spilling under a 256 MB limit |
| **R5** ✅ **done** | `SegmentReclaimer` (grace-gated unlink), `RollService` (multi-table pass + shm-pressure alert), `RollScheduler` (startup drain, daily cadence, backoff retry) | **All green** — 35 cold unit tests + 13 catalog integration tests. Grace-gated unlink verified against the **store's** clock, not the roller's; reclaims only roll-log-named segments; never the newest; rejects path traversal; idempotent; a reader mapped pre-unlink keeps reading. Backlog: 3 writer-down days drain ascending in one pass. Ascending-abort: D−2 fails ⇒ D−3 never attempted, watermark stays at D−1. Multi-table: independent watermarks, one bad table does not block the others |
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

**Resolved by R1** (kept for the record):

1. ~~Exact DuckDB iceberg DDL for partitioned/sorted table creation and properties~~ →
   `CREATE TABLE … WITH ('format-version' = '3')` (quoted key) **+** a separate
   `ALTER TABLE … SET PARTITIONED BY (day(ts))`. `PARTITIONED BY` in `CREATE` is a parser error;
   an unquoted `format_version` is silently ignored (§4 step 3). **Sort order is not settable from
   DuckDB** (`SET SORTED BY` → "Not implemented") — physical `ORDER BY` in the roll covers v1, and
   declared sort-order metadata needs the REST-create path (§1, R4).
2. ~~Is the data + roll_log two-INSERT transaction atomic?~~ → **Yes**, a single
   `POST /catalog/v1/<wh>/transactions/commit` for both tables, with `ROLLBACK` discarding both
   (§3.3). The recovery branches stay as defence in depth.
3. ~~Does Iceberg V3 `timestamp_ns` survive the DuckDB write path?~~ → **Yes, exactly** (§1, §10).

**Still open:**

4. `CutoverTracker` push-refresh from the roller vs TTL-only — TTL-only in v1; a refresh hook is
   trivial to add later.
5. Where per-table sort columns live — `:cold` config in v1; an `arena.sort_columns` schema
   metadata key (beside `arena.time_column`) is the cleaner long-term home.
6. Whether to declare sort-order metadata via the REST-create path at R4 (which makes DuckDB apply
   the sort automatically on every INSERT) or keep relying on the roll's explicit `ORDER BY`. Only
   the former survives someone hand-writing an INSERT.
