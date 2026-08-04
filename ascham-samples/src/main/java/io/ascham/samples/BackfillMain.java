package io.ascham.samples;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Generates whole completed past days of market data as fast as the machine allows — so the cold
 * tier has something real to roll without waiting days for it to accumulate.
 *
 * <pre>
 *   ./gradlew :ascham-samples:backfill --args="--days 3 --rows-per-day 50000"
 * </pre>
 *
 * <p>Days end at yesterday, never today: a day the writer might still be appending to is not
 * archivable, so backfilling into today would produce data the roller correctly refuses to touch.
 * Each day is written through a clock pinned to that date, so the writer rotates exactly as it would
 * have live, and every segment is left sealed and day-aligned — the state the roller requires.
 */
public final class BackfillMain {

    private static final String USAGE = """
            Generates completed past days of quotes and trades, ready for the cold-tier roll.

              --dir PATH           segment base directory (default /dev/shm/ito, else build/segments)
              --days N             number of completed days, ending yesterday (default 3)
              --rows-per-day N     events per day (default 50000)
              --symbols A,B,C      ticker symbols (default AAPL,MSFT,GOOG,AMZN,NVDA)
              --quotes-per-trade N roughly one trade per N events (default 10)
              --batch-rows N       rows per batch (default 4096)
              --max-batches N      batches per segment (default 512)
              --seed N             generator seed (default 42)
              --roll-cycle D       segment roll cycle, e.g. 4h, 6h, 1d (default 1d)
            """;

    private static final long NANOS_PER_DAY = 86_400L * 1_000_000_000L;

    public static void main(String[] args) {
        DemoArgs options = DemoArgs.parse(args);
        if (options.helpRequested()) {
            System.out.print(USAGE);
            return;
        }

        var dir = options.dir();
        List<String> symbols = options.symbols();
        int days = options.days();
        int rowsPerDay = options.rowsPerDay();
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate firstDay = yesterday.minusDays(days - 1L);

        System.out.printf("backfilling %d day(s) %s..%s into %s (%,d events/day)%n",
                days, firstDay, yesterday, dir.toAbsolutePath(), rowsPerDay);

        // One clock the loop advances, so a single writer rotates across day boundaries exactly as
        // a live one would — producing per-day segments rather than one undifferentiated blob.
        MutableClock clock = new MutableClock(firstDay.atStartOfDay(ZoneOffset.UTC).toInstant());
        BackfillNanoClock nanoClock = new BackfillNanoClock();

        long totalQuotes = 0;
        long totalTrades = 0;
        long start = System.nanoTime();
        try (MarketDataWriter writer = new MarketDataWriter(dir, symbols, options.batchRows(),
                options.maxBatches(), options.seed(), options.quotesPerTrade(), options.rollCycle(),
                clock, nanoClock)) {

            for (int d = 0; d < days; d++) {
                LocalDate day = firstDay.plusDays(d);
                long dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond()
                        * 1_000_000_000L;
                clock.set(day.atStartOfDay(ZoneOffset.UTC).toInstant());
                writer.heartbeat(); // rotates onto the new day

                // Spread events across the day. The step is derived from rowsPerDay so timestamps
                // always stay inside the day — a fixed step would run past midnight for large counts
                // and the roller would (correctly) refuse the day as misaligned.
                long step = NANOS_PER_DAY / (rowsPerDay + 1L);
                long quotes = 0;
                long trades = 0;
                for (int r = 0; r < rowsPerDay; r++) {
                    long ts = dayStart + r * step;
                    nanoClock.set(ts);
                    // Advance the wall clock with event time so a sub-day cycle rotates at its
                    // interval boundaries, exactly as it would live.
                    clock.set(Instant.ofEpochSecond(ts / 1_000_000_000L, ts % 1_000_000_000L));
                    if (writer.writeEvent(nanoClock.nanoTime())) {
                        trades++;
                    }
                    quotes++;
                }
                totalQuotes += quotes;
                totalTrades += trades;
                System.out.printf("  %s  %,d quotes  %,d trades%n", day, quotes, trades);
            }
        }

        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("wrote %,d quotes and %,d trades in %.1fs%n", totalQuotes, totalTrades, seconds);
        System.out.println("these days are complete and sealed — the cold tier can roll them now");
    }

    /** A UTC clock the backfill advances day by day. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Supplies the synthetic event time, so seal timestamps match the day being written. */
    private static final class BackfillNanoClock implements EpochNanoClock {
        private long nanos;

        void set(long nanos) {
            this.nanos = nanos;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }
    }
}
