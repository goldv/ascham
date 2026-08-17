# ascham-samples

Mock market data — `quotes` and `trades` — for exercising the arena end to end: schema definition,
writer, rotation, and anything you point at the resulting segments.

For the API these demos use, see [`../docs/java-guide.md`](../docs/java-guide.md).

## Quick start

```sh
# Live feed: 1000 events/s until Ctrl-C.
./gradlew :ascham-samples:runWriter
./gradlew :ascham-samples:runWriter --args="--rate 5000 --symbols AAPL,MSFT --seconds 30"

# Or generate three completed past days (fast — seconds, not days).
./gradlew :ascham-samples:backfill --args="--days 3"
```

Every main takes `--help`. Segments default to `/dev/shm/ito`, falling back to `build/segments` when
shared memory is not writable — so the demos still run in a container with a small `/dev/shm`. Both
accept `--dir`, `--symbols`, `--batch-rows`, `--max-batches`, `--seed`, `--quotes-per-trade` and
`--roll-cycle`.

To read the segments back from Java, `SnapshotReader.open` on any file under
`<dir>/quotes/` or `<dir>/trades/`; see the [reader section of the Java
guide](../docs/java-guide.md#read). Querying them through DuckDB is the job of the arrow_rdb
extension, which lives in its own repo.

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

The schemas declare `ascham.sort_key` on `(sym, ts)`, so a downstream tier knows how to sort rolled
files without separate configuration — the ordering travels with the data.

`DemoSchemas.java` is worth reading as the reference for how `ascham.*` metadata is wired up,
including the `ascham.varlen_bytes` sizing rule.

## Backfill vs live

`backfill` writes whole **completed** days, ending yesterday — never today, because a day a writer
might still be appending to is not archivable. Each day is written through a clock pinned to that
date, so segments rotate per day and end sealed and day-aligned. That is what makes a downstream roll
demonstrable in seconds instead of days.

`runWriter` writes at wall-clock time into today's segment. Its data is queryable immediately but the
current interval will not be complete until the day ends.

## Notes

- **No writer-side retention.** These demos run with `Retention.none()`: segments are reclaimed by
  whoever knows the rows are durably archived. Count-based eviction here would delete data that was
  never written down.
- **One arena base directory per catalog namespace.** Segment file names are only unique within one
  arena, so two arenas rolling into the same destination table would collide. Duplicate *data* is not
  prevented, so don't do it.
- Both tables are written from a single thread — the arena is single-writer **per table**, and
  `MarketDataWriter` holds one `RotatingWriter` each for `quotes` and `trades`.
