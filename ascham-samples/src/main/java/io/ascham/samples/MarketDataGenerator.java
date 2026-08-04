package io.ascham.samples;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.agrona.concurrent.UnsafeBuffer;

/**
 * Generates plausible mock market data: a per-symbol mid-price random walk, a quote straddling it,
 * and occasional trades that print at the bid or the ask.
 *
 * <p>Plausible matters more than realistic here. The data exists to exercise queries — zone-map
 * pruning on time and price, symbol grouping, quote/trade joins — so what it has to get right is
 * that prices move continuously per symbol, spreads stay tight and positive, trades land inside the
 * prevailing quote, and timestamps rise monotonically. It is not a market simulation.
 *
 * <p>Deterministic for a given seed, so a backfill can be regenerated identically. Allocation-free
 * once constructed: symbol, venue, and side bytes are pre-encoded into reusable buffers, because the
 * arena's append path is on the hot path and must not produce garbage.
 */
public final class MarketDataGenerator {

    /** One quote or trade, as primitive fields — nothing is allocated to hand it back. */
    public static final class Event {
        public long ts;
        public int symbolIndex;
        public int venueIndex;
        /** Quote: bid/ask; Trade: {@code px} carries the traded price. */
        public long bidPx;
        public long askPx;
        public int bidSz;
        public int askSz;
        public long px;
        public int sz;
        public boolean buy;
        public long tradeId;
        public boolean isTrade;
    }

    public static final List<String> DEFAULT_SYMBOLS = Stream.concat(IntStream.range(0, 20000).mapToObj(i -> "XS" + i), Stream.of("AAPL", "MSFT", "GOOG", "AMZN", "NVDA")).toList();
    private static final String[] VENUES = {"XNAS", "ARCA", "BATS"};
    private static final int TICK = 1; // one unit of the 1e-4 price scale

    private final String[] symbols;
    private final UnsafeBuffer[] symbolBytes;
    private final UnsafeBuffer[] venueBytes;
    private final UnsafeBuffer buyBytes;
    private final UnsafeBuffer sellBytes;
    private final long[] mid;          // per-symbol mid price, scaled 1e-4
    private final Random random;
    private final int quotesPerTrade;

    private long tradeId;
    private long cursor;               // round-robins symbols so every symbol keeps moving
    private final Event event = new Event();

    public MarketDataGenerator(List<String> symbols, long seed, int quotesPerTrade) {
        this.symbols = symbols.toArray(new String[0]);
        this.symbolBytes = encode(this.symbols);
        this.venueBytes = encode(VENUES);
        this.buyBytes = new UnsafeBuffer("B".getBytes(StandardCharsets.UTF_8));
        this.sellBytes = new UnsafeBuffer("S".getBytes(StandardCharsets.UTF_8));
        this.random = new Random(seed);
        this.quotesPerTrade = Math.max(1, quotesPerTrade);
        this.mid = new long[this.symbols.length];
        for (int i = 0; i < mid.length; i++) {
            // Opening prices between 50.0000 and 550.0000, spread across symbols.
            mid[i] = (50L + (long) (500.0 * random.nextDouble())) * DemoSchemas.PRICE_MULTIPLIER;
        }
    }

    public int symbolCount() {
        return symbols.length;
    }

    public String symbol(int index) {
        return symbols[index];
    }

    public UnsafeBuffer symbolBuffer(int index) {
        return symbolBytes[index];
    }

    public UnsafeBuffer venueBuffer(int index) {
        return venueBytes[index];
    }

    public UnsafeBuffer sideBuffer(boolean buy) {
        return buy ? buyBytes : sellBytes;
    }

    /**
     * Advances one event at {@code ts}. The returned {@link Event} is reused, so read it before the
     * next call. Roughly one in {@code quotesPerTrade} events is a trade.
     */
    public Event next(long ts) {
        int s = (int) (cursor % symbols.length);
        cursor++;

        // Random walk with a ~2 basis-point standard deviation, floored at one tick so the price can
        // always move, and clamped well above zero so a long run cannot walk into nonsense.
        long step = Math.round(random.nextGaussian() * (mid[s] * 0.0002));
        mid[s] = Math.max(DemoSchemas.PRICE_MULTIPLIER, mid[s] + step);

        int halfSpread = (1 + random.nextInt(3)) * TICK; // 1–3 ticks either side
        event.ts = ts;
        event.symbolIndex = s;
        event.venueIndex = (int) (cursor % VENUES.length);
        event.bidPx = mid[s] - halfSpread;
        event.askPx = mid[s] + halfSpread;
        event.bidSz = 100 * (1 + random.nextInt(50));
        event.askSz = 100 * (1 + random.nextInt(50));
        // Decided randomly, not from `cursor`: the symbol also rotates on `cursor`, so a counter-based
        // rule makes trades land on a fixed residue class — with 5 symbols and 1-in-10 trades, every
        // single trade would print for the same symbol, which silently guts per-symbol aggregation
        // and quote/trade joins. Still ~1 in N, still deterministic for a seed.
        event.isTrade = random.nextInt(quotesPerTrade) == 0;
        if (event.isTrade) {
            // Trades print at the touch: buys lift the ask, sells hit the bid — so a trade is always
            // inside the prevailing quote, which is what makes quote/trade joins meaningful.
            event.buy = random.nextBoolean();
            event.px = event.buy ? event.askPx : event.bidPx;
            event.sz = 100 * (1 + random.nextInt(20));
            event.tradeId = ++tradeId;
        }
        return event;
    }

    private static UnsafeBuffer[] encode(String[] values) {
        UnsafeBuffer[] out = new UnsafeBuffer[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = new UnsafeBuffer(values[i].getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }
}
