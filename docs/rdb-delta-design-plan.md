# `rdb_delta` — incremental tailing in the `arrow_rdb` DuckDB extension — design plan

Companion to [`segment-format.md`](segment-format.md) (the byte contract),
[`duckdb-extension-design-plan.md`](duckdb-extension-design-plan.md) (the original extension design,
since extracted to the standalone `arrow_rdb` repo as `rdb_scan`/`rdb_segments`/`rdb_dir`), and
[`cold-tier-design-plan.md`](cold-tier-design-plan.md). This document designs the **extension-side
changes only**: a `rdb_delta` table function that turns the extension into a kdb-style real-time
engine client — each call returns only the arena rows appended since the previous call, with the
cursor committed transactionally, so keyed/aggregated state can be maintained in native DuckDB
tables by plain SQL.

**No ascham-side changes.** No segment-format change, no `FORMAT_VERSION` bump, no new Java API.
Everything below is built on guarantees the format already makes — append-only, writer never
rewinds, release/acquire publication of batch count and lengths.

Status: **design — implementation gated on the T1–T5 milestones below.**

---

## 1. Motivation: the kdb RTE correspondence

A kdb real-time engine is a subscription (`.u.sub`), an `upd` callback over new rows, keyed mutable
state, and query functions over that state. The arena already provides the tickerplant half — it is
an ordered, replayable, append-only log. `rdb_delta` supplies the rest inside any DuckDB host:

| kdb piece | This design |
|---|---|
| TP log + replay | the arena (exists) — a fresh connection's first `rdb_delta` replays from interval start |
| `.u.sub` + `upd x;y` | `rdb_delta('trades')` in a periodic tick statement |
| keyed state `ttrades+:…` | a native DuckDB table with a `PRIMARY KEY`, updated by `INSERT … ON CONFLICT` |
| `.u.end` | the roll-cycle boundary, surfaced per row (§5) so the tick can clear state |
| `getVWAP` | plain SQL over the native state table |

The canonical kdb example

```q
upd:{[x;y]ttrades+:select size wsum price,sum size by sym from y;}
getVWAP:{select sym,vwap:price%size from ttrades where sym in x}
```

translates line-for-line:

```sql
-- once per connection (the RTE state)
CREATE TABLE ttrades(sym TEXT PRIMARY KEY, pxsz DOUBLE, sz BIGINT);

-- each tick: upd, in SQL
INSERT INTO ttrades
  SELECT sym, sum(price * size), sum(size)
  FROM rdb_delta('trades')
  GROUP BY sym
ON CONFLICT (sym) DO UPDATE SET
  pxsz = ttrades.pxsz + excluded.pxsz,
  sz   = ttrades.sz   + excluded.sz;

-- getVWAP
SELECT sym, pxsz / sz AS vwap FROM ttrades WHERE sym IN ('IBM.N', 'MSFT.O');
```

and the latest-per-key pattern (UI: latest price joined with ref data):

```sql
INSERT OR REPLACE INTO quotes_latest
  SELECT sym, ts, bid, ask, seq
  FROM rdb_delta('quotes')
  QUALIFY row_number() OVER (PARTITION BY sym ORDER BY ts DESC, seq DESC) = 1;
```

Two SQL-level facts shape both patterns:

1. **Per-key pre-collapse is mandatory.** When an `INSERT … ON CONFLICT` / `INSERT OR REPLACE`
   source contains multiple rows for the same key, DuckDB does **not** error — it silently applies
   exactly one of them and drops the rest (verified on v1.5.5: `OR REPLACE` of `(1,5),(1,6),(1,7)`
   leaves `5`; `DO UPDATE SET v = t.v + excluded.v` over source rows `10` and `20` adds only `10`).
   Which row survives is whichever the executor saw first — effectively arbitrary under a parallel
   scan. Uncollapsed ticks therefore produce silently wrong state, not errors: a latest-per-key
   upsert keeps a random row per key, an additive aggregate drops delta rows. So a delta must be
   collapsed per key first — `GROUP BY` for additive aggregates, `QUALIFY row_number() = 1` for
   latest-per-key. This mirrors kdb's own `select … by sym from y` inside `upd`.
2. **"Latest" needs a total order.** Scan emission order is nondeterministic under the parallel
   work-claim, so latest-per-key orders by data columns — the source table should carry `ts` plus a
   monotonic `seq` tiebreaker.

**Topology: one maintainer, many consumers.** A single connection owns the tick loop (a small host
program — SQL cannot loop). Consumers never call `rdb_delta`; they query the native state tables,
which DuckDB shares across all connections to the same database instance under MVCC. A tick that
updates several state tables from one delta inside one transaction leaves every derived table at
the identical frontier — consistent views by construction.

## 2. Decisions

1. **`rdb_delta(VARCHAR)` table function**, same resolution rules and output schema as
   `rdb_scan('<table-or-dir>')`, plus one virtual column (§5). Returns only rows past the
   connection's committed cursor for that table directory.
2. **Cursor state is extension-internal, per connection**, held in the DuckDB per-connection state
   registry (`ClientContext::registered_state`, `GetOrCreate<T>` — the first stateful machinery in
   this extension). Rejected alternatives: a table function cannot write catalog tables mid-query
   (no transactional context of its own, no post-commit hook from inside the scan), and
   table-function arguments must be bind-time constants, so a client-supplied watermark cannot come
   from a subquery over a bookkeeping table. A stateless position-token variant
   (`rdb_delta(table, token)`) is recorded as a fallback, not built.
3. **Transactional cursor commit** (§4): the cursor advances iff the enclosing transaction commits
   *and* the delta was fully consumed. `INSERT OR REPLACE INTO quotes_latest SELECT * FROM
   rdb_delta('quotes')` is therefore exactly-once within a connection with no extra statement.
4. **One cursor per (connection, table directory). No named cursors in v1.** The maintainer-loop
   topology needs exactly one; a shared cursor is what keeps sibling state tables consistent; an
   independent consumer lifecycle gets a second connection (and with it a second cursor) for free.
   Named cursors (`rdb_delta('quotes', 'ui1')`) are noted as a possible v2 if a real
   two-lifecycles-one-connection case appears.
5. **Reading consumes.** A bare `SELECT * FROM rdb_delta('quotes')` that runs to completion and
   commits (including autocommit) advances the cursor. This is the intended semantics — the delta
   is a stream position, not a view — and is called out prominently in user docs.
6. **Connection-lifetime state, replay as recovery.** The cursor dies with the connection; a new
   connection's first delta replays the interval from the start through the same tick SQL. This is
   kdb TP-log replay and is the designed bootstrap path. (Writer-side keyframe/snapshot batches as
   a bootstrap accelerator are explicitly out of scope here — they are an ascham-side concern and
   nothing in this design precludes them.)

## 3. Cursor model

A cursor for one table directory is a set of per-segment positions, but the format collapses it to
nearly a single triple:

- **Position = `(segment, batch_idx, row_idx)`.** `row_idx` is required (not just `batch_idx`):
  the frontier batch is in-progress and its published row count grows between polls.
- Rotation seals the predecessor before opening a successor, so **at most the newest segment is
  appending**. Older segments in the cursor are either fully consumed (dropped from the cursor) or
  consumed up to a now-sealed boundary.
- **Never-rewind makes any observed position valid forever** (`segment-format.md`, concurrency
  contract: rows below a published count are immutable; readers need no epoch/reclamation
  handshake). A stale cursor is always a correct *lower bound* — the delta is simply larger.
- Segments that appear between polls (rotation, writer restart with a bumped epoch/seq, sub-day
  roll cycles) have no cursor entry and are included **from row 0**, ordered by the existing
  `(start, cycle, seq)` segment ordering.
- Sub-day segment names are already parsed by the extension (`table_dir.cpp` regex); the cursor
  inherits that ordering unchanged.

Bind-time algorithm (delta = snapshot minus cursor):

1. Resolve + list segments; open/verify exactly as `rdb_scan` bind does today (magic, version,
   schema-SHA recompute, layout cross-check), freeze each catalog snapshot (one acquire of
   `active_batch_count`, one per `length[k]`).
2. Build the work list as `rdb_scan` does, then **filter it against the cursor**: drop
   (segment, batch) pairs wholly at or below the cursor; for the single partially-consumed batch,
   record `row_start = cursor.row_idx` so the fill covers `[row_start, row_count)`.
3. Stage — do not commit — the new frontier: the frozen snapshot's max position per segment (§4).

## 4. Transactional cursor commit — the core mechanism

The per-connection state object is registered as a `ClientContextState`, which receives transaction
lifecycle callbacks. The cursor is two-phase:

- **Stage on full consumption.** The global scan state counts emitted rows; when the last work
  item's last chunk is produced, the staged frontier (computed at bind) is marked eligible. A
  query that stops the scan early — `LIMIT`, an error mid-pipeline — never stages, so a partially
  read delta is never lost.
- **Promote on `TransactionCommit`, discard on `TransactionRollback`.** The state tables being
  updated are native DuckDB tables, so the tick statement runs inside a DuckDB transaction
  (implicit autocommit or an explicit `BEGIN … COMMIT`). If the upsert fails — constraint
  violation, cast error, anything — the rollback callback discards the staged frontier and the next
  call re-reads the identical delta. If it commits, the cursor advance is atomic-with-the-upsert
  from the connection's point of view.

Consequences:

- **Exactly-once per connection** for the canonical tick shapes, with zero extra statements and no
  seek-back recovery logic in the client.
- **Explicit transactions compose**: several `rdb_delta` reads over different tables inside one
  `BEGIN … COMMIT` all promote or all discard together — one tick can update `quotes_latest` and
  `ttrades` consistently.
- Re-reading a discarded delta is idempotent for latest-per-key (`INSERT OR REPLACE`) and safe for
  additive aggregates precisely *because* the failed transaction also rolled back the partial
  aggregate update.
- The only uncovered failure is process death, which destroys the connection and therefore the
  state the cursor was tracking — consistent by construction; recovery is decision 6's replay.

**Verify at T1** the exact `ClientContextState` virtual signatures on the pinned DuckDB v1.5.5
(`TransactionCommit` / `TransactionRollback` / query-end hooks) — this is the one API assumption
the design leans on that the extension does not already exercise.

## 5. API surface

| SQL surface | Purpose |
|---|---|
| `rdb_delta('<table-or-dir>')` | rows appended since the committed cursor; output schema = `rdb_scan` columns + `__interval_start TIMESTAMP` |
| `rdb_cursors()` | diagnostic: one row per tracked table — `(table_dir, segment, batch_idx, row_idx, rows_pending, staged)` |
| `rdb_seek('<table-or-dir>', '<segment>', batch_idx, row_idx)` / `rdb_seek('<table-or-dir>')` | reposition the cursor explicitly; the no-position form resets to zero (full replay) |

- **`__interval_start`** is a virtual column carrying the segment's roll-interval start (derived
  from the segment name, already parsed). It is the `.u.end` signal: the tick compares it to the
  previous tick's value and clears keyed state on change — `DELETE FROM quotes_latest` — inside the
  same transaction as the new interval's first upsert. Chosen over a separate diagnostic because it
  keeps boundary handling inside the one tick statement/transaction.
- `rdb_cursors` follows the `rdb_segments` style (pure diagnostic, snapshot at bind).
- `rdb_seek` exists for operational recovery and deliberate replay, not for the normal loop.
- Projection and filter pushdown, zone-map pruning on `ascham.time_column`/`ascham.stats_column`
  (sealed batches only), and exact cardinality all carry over from `rdb_scan` unchanged — the delta
  work list is a subset of the scan work list, and cardinality remains exact ("delta rows"). Note:
  a *filtered* delta still consumes the whole delta range (the cursor tracks position, not
  predicate residue) — document that filters belong in the tick's outer query if the full delta
  must reach the state tables.

## 6. Scan mechanics and lift

`rdb_delta` is `rdb_scan` plus a work-list filter and connection state — the bind/init/scan shape,
fill loops, pushdown, and parallel work-claim are reused as-is:

- `Work` gains a `row_start` field (0 for all but the one partially-consumed batch); `RdbScanFunc`'s
  fill covers `[row_start, row_count)` instead of `[0, row_count)`.
- New: the `ClientContextState` subclass (cursor map keyed by canonical table-dir path +
  `schema_sha256`, staged frontier, hook overrides) — the load-bearing new code, small and
  DuckDB-coupled by nature.
- New: full-consumption detection in the global state (emitted-rows counter vs frozen total).
- `rdb_cursors` / `rdb_seek` are thin, in the `rdb_segments` mold.
- **Optional (v1.5, measure first): cached `SegmentReader`s in the connection state**, so a poll at
  UI cadence skips the per-query re-mmap + re-SHA256 of unchanged segments and only re-freezes the
  catalog (acquire loads) of the frontier segment. Safe without locking — a connection runs one
  query at a time. Cost: the connection pins mapped (possibly retention-unlinked) shm segments for
  its lifetime; cap or age out mappings for fully-consumed segments.

Rough size: a few hundred lines in `src/scan/` plus the state class; no changes to `src/format/`.

## 7. Edge cases

| Case | Behaviour |
|---|---|
| Roll / interval boundary mid-delta | Delta spans it; rows carry their `__interval_start`; the tick clears state on change (§5). No special extension logic beyond the column. |
| Writer restart (epoch bump, new seq) | New segment, no cursor entry → included from row 0. Append-only semantics unaffected. |
| Schema rotation (SHA mismatch across segments) | Emit up to the boundary in this call; the next call errors with the existing cross-segment schema error until the cursor is `rdb_seek`'d past the boundary. Mirrors `rdb_scan`'s hard-failure stance. |
| Cursor references a reclaimed (unlinked) segment | Hard error naming the segment — this is data loss, not something to paper over. Operational rule: retention must exceed the tick cadence (arena retention currently defaults to `none()`). |
| Empty delta | Zero rows; nothing staged; cursor unchanged. The tick's upsert is a no-op. |
| `LIMIT` / aborted scan | Nothing staged; cursor unchanged; next call re-reads (§4). |
| Multiple `rdb_delta('t')` references in one query | Rejected at bind (one staged frontier per table per transaction; self-joining a stream is not meaningful). |

## 8. Limits (documented, accepted)

- The tick loop needs a host program; pure SQL cannot loop. The host is trivial (any DuckDB client
  with a timer).
- State is connection-lifetime; durability of derived state is out of scope (a host may run DuckDB
  on a file database if it wants tick-to-tick persistence — the cursor still resets on reconnect,
  and replay reconverges the state tables; make the replay tick idempotent, i.e. latest-per-key or
  rebuild-from-zero after a `DELETE`).
- Only decomposable aggregates maintain incrementally (sum/count/min/max/last); holistic ones
  (median, exact distinct) are computed on demand over state or accept a full `rdb_scan`.

## 9. Testing

- **sqllogictests** (`test/sql/rdb_delta.test`, alongside the existing suite): append → delta →
  append → delta sequences against fixture segments (extend the fixture generator to write in
  steps); empty delta; delta spanning a segment boundary; sub-day interval boundary and
  `__interval_start` change; cross-segment schema-change error; multi-reference rejection.
- **Transactional semantics**: `BEGIN; SELECT … rdb_delta …; ROLLBACK;` → `rdb_cursors` unmoved and
  the next delta identical; failed upsert (constraint violation) → same; `LIMIT 1` → cursor
  unmoved; successful autocommit tick → cursor advanced, next delta empty.
- **Kdb-pattern integration**: the §1 VWAP and latest-per-key ticks run repeatedly against a
  growing fixture; state tables equal a from-scratch `rdb_scan` recomputation after every tick
  (the oracle check).
- **Upstream-semantics canary**: a test pinning the §1 duplicate-conflict behaviour (duplicates
  silently collapsed, one arbitrary survivor — observed on v1.5.5, not documented contract), so a
  DuckDB upgrade that changes it to an error or last-wins is caught by the suite, not by users.
- **Live soak**: the ascham sample `MarketDataWriter` appending while a host loops the tick;
  assert state-table row counts and VWAP values converge with a from-scratch recompute; kill the
  writer mid-append and verify the tick keeps returning consistent (frozen-snapshot) deltas.
- **Parallel scan**: delta results identical at `threads=1` vs `threads=8` (order-insensitive
  compare), reusing the existing pushdown-test pattern.

## 10. Milestones

| # | Deliverable | Exit criteria |
|---|---|---|
| **T1** | `ClientContextState` spike on v1.5.5: register per-connection state, observe commit/rollback callbacks from a table function's bind | hook signatures confirmed; a toy counter advances on commit, not on rollback |
| **T2** | Cursor model + `rdb_delta` (bind filter, `row_start`, full-consumption staging), `__interval_start` column | append/delta sqllogictests green; empty delta; segment-boundary delta |
| **T3** | Transactional commit wired to the hooks; `rdb_cursors`, `rdb_seek` | §9 transactional-semantics tests green |
| **T4** | Edge cases (schema rotation, reclaimed segment, multi-reference rejection); kdb-pattern oracle tests | full suite green at threads=1 and threads=8 |
| **T5** | Live soak vs the ascham sample writer; docs (README section: tick patterns, "reading consumes", retention-vs-cadence rule) | soak green incl. writer-kill; README merged |

## 11. Risks

1. **`ClientContextState` hook availability/semantics on v1.5.5** — the design's one new DuckDB-API
   dependency; retired first at T1. Fallback if hooks prove unusable: explicit
   `CALL rdb_advance('t')` two-step (weaker — advance is no longer atomic with the upsert; the
   client guards re-application with the `seq` column).
2. **Full-consumption detection vs operator behaviours** — pipelines that legitimately stop pulling
   early (LIMIT is the known one) must never stage; the T2/T3 tests pin this. Conservative rule:
   when in doubt, don't stage — a re-read is always safe, a skipped delta never is.
3. **First stateful machinery in the extension** — scoped per-connection (no cross-connection
   locking by construction), but upgrade-fragility of internals-adjacent APIs is already this
   extension's known cost (`docs/known-issues.md` in `arrow_rdb`); the hooks add one more surface
   to the version-pin checklist.
4. **Cadence vs retention** — a stalled maintainer whose cursor falls behind retention loses data
   loudly (§7). The `rdb_cursors().rows_pending` column exists so hosts can alarm on lag.
5. **Cached-reader mapping growth** (if the v1.5 cache lands) — bounded by aging out
   fully-consumed segments; measured before enabling by default.
