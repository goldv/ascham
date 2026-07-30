package io.ito.demo;

import io.ito.arena.rotate.DailyRotationPolicy;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import io.ito.arena.write.GenericAppender;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;
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
    private final MarketDataGenerator generator;

    // Per-event values captured before the append lambda runs. RotatingWriter may re-invoke a lambda
    // on a fresh segment when the current one fills, so the lambda must be a pure function of these
    // fields — it may not advance the generator or mutate anything.
    private long ts;
    private UnsafeBuffer sym;
    private int symLen;
    private UnsafeBuffer venue;
    private int venueLen;
    private long bidPx;
    private long askPx;
    private int bidSz;
    private int askSz;
    private long px;
    private int sz;
    private UnsafeBuffer side;
    private long tradeId;

    private final Consumer<GenericAppender> quoteRow = a -> {
        a.beginRow();
        a.setLong(0, ts);
        a.setBytes(1, sym, 0, symLen);
        a.setLong(2, bidPx);
        a.setLong(3, askPx);
        a.setInt(4, bidSz);
        a.setInt(5, askSz);
        a.setBytes(6, venue, 0, venueLen);
        a.endRow();
    };

    private final Consumer<GenericAppender> tradeRow = a -> {
        a.beginRow();
        a.setLong(0, ts);
        a.setBytes(1, sym, 0, symLen);
        a.setLong(2, px);
        a.setInt(3, sz);
        a.setBytes(4, side, 0, 1);
        a.setLong(5, tradeId);
        a.setBytes(6, venue, 0, venueLen);
        a.endRow();
    };

    public MarketDataWriter(Path baseDir, List<String> symbols, int batchRows, int maxBatches,
                            long seed, int quotesPerTrade, Clock clock, EpochNanoClock nanoClock) {
        this.generator = new MarketDataGenerator(symbols, seed, quotesPerTrade);
        SegmentDirectory quotesDir = new SegmentDirectory(baseDir, "quotes");
        SegmentDirectory tradesDir = new SegmentDirectory(baseDir, "trades");
        // A fresh epoch per process, so readers can tell this writer from a previous instance.
        long epoch = Math.max(quotesDir.latestEpoch().orElse(0), tradesDir.latestEpoch().orElse(0)) + 1;
        this.quotes = RotatingWriter.open(quotesDir, DemoSchemas.quotes(batchRows), maxBatches, epoch,
                new DailyRotationPolicy(), clock, nanoClock);
        this.trades = RotatingWriter.open(tradesDir, DemoSchemas.trades(batchRows), maxBatches, epoch,
                new DailyRotationPolicy(), clock, nanoClock);
    }

    /**
     * Generates one event at {@code ts} and appends it: always a quote, and a trade too when the
     * generator says this event printed. Returns true if a trade was written.
     */
    public boolean writeEvent(long ts) {
        MarketDataGenerator.Event e = generator.next(ts);
        this.ts = e.ts;
        this.sym = generator.symbolBuffer(e.symbolIndex);
        this.symLen = sym.capacity();
        this.venue = generator.venueBuffer(e.venueIndex);
        this.venueLen = venue.capacity();
        this.bidPx = e.bidPx;
        this.askPx = e.askPx;
        this.bidSz = e.bidSz;
        this.askSz = e.askSz;
        quotes.append(quoteRow);

        if (e.isTrade) {
            this.px = e.px;
            this.sz = e.sz;
            this.side = generator.sideBuffer(e.buy);
            this.tradeId = e.tradeId;
            trades.append(tradeRow);
            return true;
        }
        return false;
    }

    /** Advances both tables' liveness heartbeats (and rotates if the day has turned). */
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
