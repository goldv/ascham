# demo

Mock market data — `quotes` and `trades` — for exercising the whole stack: writer → arena →
DuckDB queries → cold-tier roll → Iceberg.

## Quick start

```sh
# Generate three completed past days (fast — seconds, not days).
./gradlew :demo:backfill --args="--days 3"

# Query them live from the arena.
duckdb -unsigned -c "LOAD 'arena-duckdb/build/arena.duckdb_extension';
                     SET arena_dir='/dev/shm/ito';
                     SELECT sym, count(*) n, sum(sz) volume FROM trades GROUP BY sym ORDER BY volume DESC"

# Roll them into Iceberg and reclaim the shared memory (needs the dev stack).
docker compose -f dev/docker-compose.yml up -d
./gradlew :demo:roll
```

For a live feed instead of a backfill:

```sh
./gradlew :demo:runWriter                                   # 1000 events/s until Ctrl-C
./gradlew :demo:runWriter --args="--rate 5000 --seconds 30"
```

Every main takes `--help`. Segments default to `/dev/shm/ito`, falling back to `build/segments`
when shared memory is not writable.

## The data

A per-symbol mid-price random walk, a quote straddling it, and trades printing at the touch.
Plausible rather than realistic — what it has to get right is that prices move continuously per
symbol, spreads stay tight and positive, **trades land inside the prevailing quote** (so quote/trade
joins mean something), and timestamps rise monotonically. Deterministic for a given `--seed`, so a
backfill is reproducible.

| | |
|---|---|
| `quotes` | `ts`, `sym`, `bid_px`, `ask_px`, `bid_sz`, `ask_sz`, `venue` |
| `trades` | `ts`, `sym`, `px`, `sz`, `side`, `trade_id`, `venue` |

**Prices are scaled integers, not floats** — `Int64` with an implied 1e-4 scale, so `1234567` means
123.4567. Binary floating point accumulates error and compares badly; a scaled integer is exact.
Divide by 10000.0 to display.

The schemas declare `arena.sort_key` on `(sym, ts)`, so the cold tier knows how to sort rolled files
without separate configuration — the ordering travels with the data.

## Queries worth trying

```sql
-- VWAP and volume by symbol
SELECT sym, count(*) n, sum(sz) volume, round(sum(px*sz)/sum(sz)/10000.0, 4) vwap
FROM trades GROUP BY sym ORDER BY volume DESC;

-- Each trade against the quote prevailing at the time
SELECT t.sym, t.px/10000.0 trade, q.bid_px/10000.0 bid, q.ask_px/10000.0 ask
FROM trades t ASOF JOIN quotes q ON t.sym = q.sym AND t.ts >= q.ts LIMIT 10;

-- Freshness: run twice a few seconds apart while runWriter is going
SELECT count(*) FROM quotes;
```

## Backfill vs live

`backfill` writes whole **completed** days, ending yesterday — never today, because a day the writer
might still be appending to is not archivable. Each day is written through a clock pinned to that
date, so segments rotate per day and end sealed and day-aligned: exactly the state the cold tier
requires. That is what makes the roll demonstrable in seconds instead of days.

`runWriter` writes at wall-clock time into today's segment. Its data is queryable immediately but
will not roll until the day completes.

## Notes

- **No writer-side retention.** Segments are reclaimed by the cold tier once their rows are durably
  archived; count-based eviction here would delete data that was never written down.
- **One arena base directory per catalog namespace.** Segment file names are only unique within one
  arena, so two arenas rolling into the same catalog table would collide. The reclaimer records and
  verifies which arena each roll came from and refuses to delete across that boundary — but the
  duplicate *data* is not prevented, so don't do it.
- After a roll, each table's newest segment stays in the arena even though it is archived: a writer
  may still be appending to it, so it is never reclaimed.
