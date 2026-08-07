# Real-time engine (kdb-style RTE) over ascham — design options discussion

Status: **discussion only** — no implementation proposed yet. Decisions taken so far: derived/keyed
state will be **republished to the arena append-only** (latest-per-key resolved at read time), not
held in a mutable structure.

## Context

ascham today: single-writer append-only shared-memory Arrow segments with release/acquire
publication on batch count + lengths; readers freeze a snapshot per open. The DuckDB C++ extension
lives externally at `~/src/arrow_rdb` (fork of the deleted `arena-duckdb`, ported at ascham
`4fa0c6f`): `rdb_scan` / `rdb_segments` / `SET rdb_dir` + replacement scan, per-query bind snapshot,
zone-map pruning on `ascham.time_column`/`ascham.stats_column` (sealed batches only), exact
cardinality, parallel per-(segment,batch) work items, sub-day segment names supported.

Goal: kdb RTE-style pipelines (subscribe → upd → keyed state → query, e.g. VWAP) and live UI views
(keyed table ⋈ ref data, filtered/sorted, streamed updates), ideally with DuckDB providing the
analytics.

## The four missing pieces (kdb anatomy → ascham)

| kdb piece | ascham answer |
|---|---|
| TP log + replay | already exists — the arena is an ordered, replayable append-only log |
| `.u.sub` / `upd` | **Tailer API** (roadmap item 2): cursor of per-segment/batch watermarks, acquire-poll `active_batch_count` + `length[k]`, deliver zero-copy views of `[prev,new)` |
| keyed state `ttrades+:` | **append-only republish** (decided): RTE writes upsert rows to a derived arena table; "keyed" = latest-per-key at read time |
| `.u.end` | roll-cycle boundary — new interval starts empty |
| `getVWAP` | DuckDB over the derived table — the subject of this discussion |

### Shape of a republished keyed stream
- One full row per key-update (upsert semantics); latest row per key = current state.
- Needs a total order for "latest": `ts` plus a monotonic `seq` column as tiebreaker (scan emission
  order is nondeterministic under parallelism).
- Deletes: tombstone column if ever needed; EOD clear falls out of the roll boundary.
- Everything obeys the existing concurrency contract — zero new memory-ordering work.

## A. Query-surface vehicles

**A1. `arrow_rdb` C++ extension (exists).**
Pros: any DuckDB host (CLI, Python, a view server in any language) sees live data with per-query
snapshot freshness for free; no JVM in the read path; hardened reader core + conformance gate.
Cons: C++ maintenance; pinned to DuckDB v1.5.5 (uses `TableFilterState`/`FilterSelection`
internals — flagged in its own known-issues); fills copy rather than zero-copy wrap; POSIX-only.

**A2. Java in-process bridge** (`ArenaTableReader` blueprint in `docs/flight-sql-design-plan.md`
§6.2: snapshot roots → `arrow-c-data` → `registerArrowStream` → embedded `duckdb_jdbc`).
Pros: DuckDB analytics inside the same JVM as the RTE/tailer; `arrow-c-data` already a core dep; no
dependence on DuckDB C++ internals. Cons: JVM-only surface; must be built; re-register per query.

**A3. Flight SQL server** (designed, unbuilt). Network SQL for any client; A2 is a prerequisite.
Defer — not needed for either use case.

These compose rather than compete: A1 for external tooling and a non-JVM view server; A2 if the
view server is the RTE process itself.

## B. Keyed-table support in the extension — lift options

Facts that bound the lift (from exploration of `~/src/arrow_rdb`):
- Field-level `ascham.*` metadata (`sort_key`, `ref`) is **not decoded** — schema.cpp reads only the
  two table-level keys. Decoding it is ~20–30 lines and is the prerequisite for schema-driven keys.
- No cross-query state of any kind exists (no ObjectCache/statics); everything (mmap, SHA-256,
  layout, catalog snapshot) is rebuilt per query.
- Catalog entry bytes 56–63 are spare — room for a keyframe marker, but that's an ascham-side
  format change + `FORMAT_VERSION` bump + conformance-corpus regen (extension cannot lead).
- Work list is built oldest→newest; reversing it is a natural newest-first pass.
- Reader core is DuckDB-free — a keyed index could live in `src/format/` and be tested without DuckDB.

**B1. Pure SQL, zero extension change.**
`QUALIFY row_number() OVER (PARTITION BY sym ORDER BY ts DESC, seq DESC) = 1` (or `max_by`) over
`rdb_scan`. Lift: none. Pros: works today; composes with any analytics. Cons: scans every row since
interval start on every query; no key pruning; cost grows through the day.

**B2a. Keyframes by convention — writer-side only, no format/extension change.** ⭐
RTE periodically emits a full keyed snapshot ("keyframe") and maintains a monotonically increasing
`generation` column, declared as `ascham.stats_column`. Keyframe bumps the generation. A query with
`WHERE generation >= G_last_keyframe` is pruned by the **existing** stat zone maps — old batches
(`stat_max < G`) are skipped at init. `G` discoverable via `rdb_segments` max(stat_max) or tracked
by the server. Lift: republisher logic + query pattern only. Pros: bounds per-query scan to
keyframe-interval + deltas; nothing changes in format or extension. Cons: spends the single stats
column on generation (no second zone-mapped stat); keyframe cadence/size tuning; queries must
include the generation predicate.

**B2b. Format-level keyframe flag** (spare catalog bytes) + extension pruning + version bump.
More principled, materially more work across both repos + corpus regen. Hold unless B2a's
constraints bite.

**B3. `rdb_latest('table')` table function.**
Reverse-order scan building key→(segment,batch,row) map in bind/init, emit one row per key; with
keyframes, terminate at the newest keyframe (complete key universe). Prereq: field-metadata decode
(key from `ascham.sort_key` or a parameter). Lift: moderate — a few hundred lines reusing
`SegmentReader` random access (`fixed`/`varlen` are per-(batch,row,col) already). Pros: keyed
semantics in one place; `SELECT * FROM rdb_latest('px')` joins/aggregates naturally; near
O(keys+deltas) with keyframes. Cons: C++ work; hash build serializes in bind; still rebuilt per query.

**B4. Cross-query incremental keyed cache** (`DatabaseInstance`-scoped, keyed on table dir +
`schema_sha256`; append-only contract makes `(segment, batch, frozen row_count)` monotonic, so a
cached index extends by folding in deltas). Pros: per-query cost ∝ new rows; makes UI-cadence
polling nearly free. Cons: first stateful machinery in the extension; locking across connections;
cached mmaps pin unlinked shm segments; invalidation on schema rotation. This is the v2 move once
measurements justify it.

## C. Live UI via DuckDB — streaming options

DuckDB has no incremental/continuous materialized views, so "live" is either recompute-on-poll or
incrementality maintained outside DuckDB.

**C1. Poll-and-requery + key-diff server.** ⭐ (exploratory starting point)
View server (DuckDB + extension in any host, or the JVM via A2) re-runs the SQL view every
100–500 ms, diffs vs previous result by key, pushes adds/updates/removes (Arrow IPC or JSON) over
WebSocket. Pros: arbitrary SQL views (joins to ref data, ASOF to quotes, aggregations, top-N);
snapshot-consistent; per-view state = last result only; filter/sort change = just a new query.
Cons: recompute ∝ view size each tick (mitigated by B2a/B3/B4); latency floor = cadence; per-query
bind overhead (re-mmap + SHA-256 per segment — small for a handful of segments, but real at high
cadence × many views; B4 is the cure if it shows up).

**C2. Watermark-incremental hybrid.** DuckDB computes only rows past a watermark (ts predicate →
zone-map pruned); server merges deltas into its keyed view state; filter/sort applied over the small
state (server code or a second DuckDB pass). Pros: per-tick cost ∝ deltas. Cons: view logic splits
between SQL and merge code — loses the "just SQL" property; awkward for global aggregates.

**C3. Stream deltas to the client; duckdb-wasm in the browser.** Server tails the keyed table and
streams Arrow deltas; the client holds tables in duckdb-wasm and runs filter/sort/analytics locally
(client doesn't need the extension — it queries locally-fed tables). Pros: server trivially cheap;
per-client interactive views free. Cons: full keyed table shipped to every client; heavier client;
ref-data join client-side.

## D. Suggested exploratory sequence (if/when this proceeds)

1. **Tailer API** in core (roadmap item 2) — needed by every option; enables the RTE harness
   (subscribe/upd/timers/roll events) and the republisher.
2. **Republished keyed table + B1 pure SQL** — validate the model end-to-end with the existing
   extension; measure query cost vs row count and bind overhead at UI cadence.
3. **B2a generation-keyframes** when scan cost hurts — writer-side only.
4. **C1 poll-and-diff view server** for the UI experiment.
5. B3/B4 and A2/A3 only as measurements or deployment shape demand.
