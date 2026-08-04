package io.ascham.samples;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.SystemEpochNanoClock;

/**
 * Writes live mock market data into the arena until interrupted — the demo's producer.
 *
 * <pre>
 *   ./gradlew :ascham-samples:runWriter
 *   ./gradlew :ascham-samples:runWriter --args="--rate 5000 --symbols AAPL,MSFT --seconds 30"
 * </pre>
 *
 * Query it while it runs, e.g. through the arena DuckDB extension:
 * <pre>
 *   duckdb -unsigned -c "LOAD 'arena-duckdb/build/arena.duckdb_extension';
 *                        SET arena_dir='/dev/shm/ito';
 *                        SELECT sym, count(*), max(ask_px)/10000.0 FROM quotes GROUP BY sym"
 * </pre>
 */
public final class MarketDataWriterMain {

    private static final String USAGE = """
            Writes live mock quotes and trades into the arena.

              --dir PATH             segment base directory (default /dev/shm/ito, else build/segments)
              --rate N               events per second (default 1000)
              --symbols A,B,C        ticker symbols (default AAPL,MSFT,GOOG,AMZN,NVDA)
              --quotes-per-trade N   roughly one trade per N events (default 10)
              --batch-rows N         rows per batch (default 4096)
              --max-batches N        batches per segment (default 512)
              --seconds N            stop after N seconds (default 0 = run until Ctrl-C)
              --seed N               generator seed (default 42)
              --roll-cycle D         segment roll cycle, e.g. 4h, 6h, 1d (default 1d)
            """;

    public static void main(String[] args) throws Exception {
        DemoArgs options = DemoArgs.parse(args);
        if (options.helpRequested()) {
            System.out.print(USAGE);
            return;
        }

        var dir = options.dir();
        List<String> symbols = options.symbols();
        int rate = options.rate();
        SystemEpochNanoClock nanoClock = new SystemEpochNanoClock();

        System.out.printf("writing quotes + trades to %s%n", dir.toAbsolutePath());
        System.out.printf("  %d events/s, symbols %d, ~1 trade per %d events%n",rate, symbols.size(), options.quotesPerTrade());
        System.out.println("  Ctrl-C to stop");

        long deadlineNanos = options.seconds() > 0
                ? System.nanoTime() + options.seconds() * 1_000_000_000L
                : Long.MAX_VALUE;
        long intervalNanos = 1_000_000_000L / Math.max(1, rate);

        try (MarketDataWriter writer = new MarketDataWriter(dir, symbols, options.batchRows(),
                options.maxBatches(), options.seed(), options.quotesPerTrade(),
                options.rollCycle(), Clock.systemUTC(), nanoClock)) {

            // Closing the writer seals the trailing batch of each segment, which is what lets the
            // cold tier archive it — so shutdown must run even on Ctrl-C.
            Thread main = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                main.interrupt();
                try {
                    main.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            long quotes = 0;
            long trades = 0;
            long lastHeartbeat = System.nanoTime();
            long lastReport = lastHeartbeat;

            while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadlineNanos) {
                if (writer.writeEvent(nanoClock.nanoTime())) {
                    trades++;
                }
                quotes++;

                long now = System.nanoTime();
                if (now - lastHeartbeat >= 100_000_000L) { // 100 ms
                    writer.heartbeat();
                    lastHeartbeat = now;
                }
                if (now - lastReport >= 5_000_000_000L) { // 5 s
                    System.out.printf("  %,d quotes  %,d trades%n", quotes, trades);
                    lastReport = now;
                }
                LockSupport.parkNanos(intervalNanos);
            }
            System.out.printf("wrote %,d quotes and %,d trades%n", quotes, trades);
        }
    }
}
