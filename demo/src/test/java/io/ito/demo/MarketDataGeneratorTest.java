package io.ito.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The generated data must be plausible enough for the queries the demo exists to show. */
class MarketDataGeneratorTest {

    private static final List<String> SYMBOLS = MarketDataGenerator.DEFAULT_SYMBOLS;

    @Test
    void tradesAreSpreadAcrossEverySymbol() {
        // Regression: deciding trades from the same counter that rotates symbols made every trade
        // land on one symbol whenever the trade cadence and symbol count shared a factor — with 5
        // symbols and 1-in-10 trades, 100% of trades printed for NVDA, silently gutting per-symbol
        // aggregation and quote/trade joins.
        MarketDataGenerator generator = new MarketDataGenerator(SYMBOLS, 42L, 10);
        Map<String, Integer> tradesPerSymbol = new HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            MarketDataGenerator.Event e = generator.next(i);
            if (e.isTrade) {
                tradesPerSymbol.merge(generator.symbol(e.symbolIndex), 1, Integer::sum);
            }
        }
        assertThat(tradesPerSymbol).hasSize(SYMBOLS.size());
        // Roughly even: no symbol may take less than half of a fair share.
        int fairShare = tradesPerSymbol.values().stream().mapToInt(Integer::intValue).sum() / SYMBOLS.size();
        assertThat(tradesPerSymbol.values()).allSatisfy(n -> assertThat(n).isGreaterThan(fairShare / 2));
    }

    @Test
    void tradesArriveAtRoughlyTheRequestedRate() {
        MarketDataGenerator generator = new MarketDataGenerator(SYMBOLS, 7L, 10);
        int trades = 0;
        for (int i = 0; i < 100_000; i++) {
            if (generator.next(i).isTrade) {
                trades++;
            }
        }
        assertThat(trades).isBetween(8_000, 12_000); // ~1 in 10
    }

    @Test
    void everyQuoteHasAPositiveSpreadAroundTheMid() {
        MarketDataGenerator generator = new MarketDataGenerator(SYMBOLS, 3L, 10);
        for (int i = 0; i < 20_000; i++) {
            MarketDataGenerator.Event e = generator.next(i);
            assertThat(e.askPx).isGreaterThan(e.bidPx);
            assertThat(e.bidPx).isPositive();
            assertThat(e.bidSz).isPositive();
            assertThat(e.askSz).isPositive();
        }
    }

    @Test
    void tradesPrintInsideThePrevailingQuote() {
        // What makes a quote/trade join meaningful: a print is always at the touch, never outside it.
        MarketDataGenerator generator = new MarketDataGenerator(SYMBOLS, 11L, 5);
        int checked = 0;
        for (int i = 0; i < 20_000; i++) {
            MarketDataGenerator.Event e = generator.next(i);
            if (e.isTrade) {
                assertThat(e.px).isBetween(e.bidPx, e.askPx);
                assertThat(e.sz).isPositive();
                checked++;
            }
        }
        assertThat(checked).isPositive();
    }

    @Test
    void tradeIdsAreUniqueAndMonotonic() {
        MarketDataGenerator generator = new MarketDataGenerator(SYMBOLS, 5L, 4);
        Set<Long> seen = new HashSet<>();
        long previous = 0;
        for (int i = 0; i < 20_000; i++) {
            MarketDataGenerator.Event e = generator.next(i);
            if (e.isTrade) {
                assertThat(e.tradeId).isGreaterThan(previous);
                assertThat(seen.add(e.tradeId)).isTrue();
                previous = e.tradeId;
            }
        }
    }

    @Test
    void pricesWanderWithoutRunningAway() {
        // A random walk with no floor can drift to nonsense over a long run; prices must stay in a
        // range a human would accept as market data.
        MarketDataGenerator generator = new MarketDataGenerator(SYMBOLS, 13L, 10);
        long lowest = Long.MAX_VALUE;
        long highest = Long.MIN_VALUE;
        for (int i = 0; i < 200_000; i++) {
            MarketDataGenerator.Event e = generator.next(i);
            lowest = Math.min(lowest, e.bidPx);
            highest = Math.max(highest, e.askPx);
        }
        assertThat(lowest).isGreaterThan(DemoSchemas.PRICE_MULTIPLIER);          // above 1.0000
        assertThat(highest).isLessThan(100_000L * DemoSchemas.PRICE_MULTIPLIER); // below 100,000
    }

    @Test
    void theSameSeedProducesTheSameData() {
        // Determinism is what makes a backfill reproducible.
        assertThat(fingerprint(new MarketDataGenerator(SYMBOLS, 99L, 10)))
                .isEqualTo(fingerprint(new MarketDataGenerator(SYMBOLS, 99L, 10)))
                .isNotEqualTo(fingerprint(new MarketDataGenerator(SYMBOLS, 100L, 10)));
    }

    private static long fingerprint(MarketDataGenerator generator) {
        long hash = 17;
        for (int i = 0; i < 2_000; i++) {
            MarketDataGenerator.Event e = generator.next(i);
            hash = hash * 31 + e.bidPx + e.askPx + e.symbolIndex + (e.isTrade ? e.px : 0);
        }
        return hash;
    }
}
