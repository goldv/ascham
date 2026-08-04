package io.ascham.samples;

import io.ascham.rotate.RollCycle;
import io.ascham.rotate.RotatingWriter;
import io.ascham.rotate.SegmentDirectory;
import io.ascham.write.Appender;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Writes generated market data into the arena's {@code quotes} and {@code trades} tables.
 *
 * <p>Two {@link RotatingWriter}s, because the arena is single-writer <em>per table</em>. Both are
 * driven from one thread, so ordering between the two tables is consistent: a trade is never written
 * before the quote it printed against.
 *
 * <p><b>No writer-side retention.</b> Segments are reclaimed by the cold tier once their rows are
 * durably archived; count-based eviction here would delete data that was never written down.
 */
public final class MarketDataWriter implements AutoCloseable {

    private final RotatingWriter quotes;
    private final RotatingWriter trades;
    private final Appender quoteAppender;
    private final Appender tradeAppender;
    private final MarketDataGenerator generator;

    public MarketDataWriter(Path baseDir, List<String> symbols, int batchRows, int maxBatches,
                            long seed, int quotesPerTrade, RollCycle cycle, Clock clock,
                            EpochNanoClock nanoClock) {
        this.generator = new MarketDataGenerator(symbols, seed, quotesPerTrade);
        SegmentDirectory quotesDir = new SegmentDirectory(baseDir, "quotes");
        SegmentDirectory tradesDir = new SegmentDirectory(baseDir, "trades");
        // A fresh epoch per process, so readers can tell this writer from a previous instance.
        long epoch = Math.max(quotesDir.latestEpoch().orElse(0), tradesDir.latestEpoch().orElse(0)) + 1;
        this.quotes = RotatingWriter.open(quotesDir, DemoSchemas.quotes(batchRows), maxBatches, epoch,
                cycle, clock, nanoClock);
        this.trades = RotatingWriter.open(tradesDir, DemoSchemas.trades(batchRows), maxBatches, epoch,
                cycle, clock, nanoClock);
        this.quoteAppender = quotes.appender();
        this.tradeAppender = trades.appender();
    }

    /**
     * Generates one event at {@code ts} and appends it: always a quote, and a trade too when the
     * generator says this event printed. Returns true if a trade was written.
     */
    public boolean writeEvent(long ts) {
        MarketDataGenerator.Event e = generator.next(ts);
        UnsafeBuffer sym = generator.symbolBuffer(e.symbolIndex);
        UnsafeBuffer venue = generator.venueBuffer(e.venueIndex);

        Appender q = quoteAppender;
        q.beginRow();
        q.setLong(0, e.ts);
        q.setBytes(1, sym, 0, sym.capacity());
        q.setLong(2, e.bidPx);
        q.setLong(3, e.askPx);
        q.setInt(4, e.bidSz);
        q.setInt(5, e.askSz);
        q.setBytes(6, venue, 0, venue.capacity());
        q.endRow();

        if (e.isTrade) {
            UnsafeBuffer side = generator.sideBuffer(e.buy);
            Appender t = tradeAppender;
            t.beginRow();
            t.setLong(0, e.ts);
            t.setBytes(1, sym, 0, sym.capacity());
            t.setLong(2, e.px);
            t.setInt(3, e.sz);
            t.setBytes(4, side, 0, 1);
            t.setLong(5, e.tradeId);
            t.setBytes(6, venue, 0, venue.capacity());
            t.endRow();
            return true;
        }
        return false;
    }

    /** Advances both tables' liveness heartbeats (and rotates if the roll interval has turned). */
    public void heartbeat() {
        quotes.heartbeat();
        trades.heartbeat();
    }

    public MarketDataGenerator generator() {
        return generator;
    }

    @Override
    public void close() {
        try {
            quotes.close();
        } finally {
            trades.close(); // close the second even if the first throws, or its segment stays unsealed
        }
    }
}
