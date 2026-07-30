package io.ito.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.BatchView;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.rotate.SegmentDirectory;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The demo schemas must be valid arena schemas, and the writer must produce roll-ready segments. */
class DemoSchemasTest {

    @TempDir
    Path base;

    @Test
    void bothSchemasValidateAsArenaSchemas() {
        // ArenaSchema.load runs the full validator, so constructing them at all is the assertion.
        ArenaSchema quotes = DemoSchemas.quotes(4096);
        ArenaSchema trades = DemoSchemas.trades(4096);

        assertThat(quotes.metadata().table()).isEqualTo("quotes");
        assertThat(quotes.metadata().timeColumn()).isEqualTo("ts");
        assertThat(trades.metadata().statsColumn()).contains("px");
        assertThat(quotes.columnCount()).isEqualTo(7);
        assertThat(trades.columnCount()).isEqualTo(7);
    }

    @Test
    void theSortOrderTravelsWithTheSchema() {
        // arena.sort_key declares (sym, ts), so the cold tier needs no separate configuration to
        // know how to sort rolled files.
        assertThat(DemoSchemas.sortColumns(DemoSchemas.quotes(4096))).containsExactly("sym", "ts");
        assertThat(DemoSchemas.sortColumns(DemoSchemas.trades(4096))).containsExactly("sym", "ts");
    }

    @Test
    void varlenBudgetsScaleWithTheBatchSize() {
        // arena.varlen_bytes is a per-batch budget for the whole column, so it must track batch_rows;
        // a fixed budget would silently force early batch migration at larger batch sizes.
        long small = varlenBytes(DemoSchemas.quotes(1024), "sym");
        long large = varlenBytes(DemoSchemas.quotes(4096), "sym");
        assertThat(large).isEqualTo(small * 4);
    }

    @Test
    void theWriterProducesSealedDayAlignedSegments() {
        long dayStart = Instant.parse("2026-07-27T00:00:00Z").getEpochSecond() * 1_000_000_000L;
        FixedNanoClock nanoClock = new FixedNanoClock(dayStart);
        try (MarketDataWriter writer = new MarketDataWriter(base, MarketDataGenerator.DEFAULT_SYMBOLS,
                256, 64, 42L, 10, fixedClock("2026-07-27T00:00:00Z"), nanoClock)) {
            for (int i = 0; i < 2_000; i++) {
                nanoClock.set(dayStart + i * 1_000_000L);
                writer.writeEvent(nanoClock.nanoTime());
            }
        }

        // Both tables exist and every row-bearing batch is sealed with published stats — the state
        // the cold tier requires before it will archive a day.
        for (String table : List.of("quotes", "trades")) {
            List<SegmentDirectory.SegmentName> segments = new SegmentDirectory(base, table).list();
            assertThat(segments).isNotEmpty();
            try (SnapshotReader reader = SnapshotReader.open(segments.get(0).path())) {
                List<BatchView> withRows = reader.snapshot().batches().stream()
                        .filter(b -> b.rowCount() > 0).toList();
                assertThat(withRows).isNotEmpty();
                assertThat(withRows).allMatch(BatchView::sealed);
                assertThat(withRows).allSatisfy(b -> {
                    assertThat(b.tsMin()).isGreaterThanOrEqualTo(dayStart);
                    assertThat(b.tsMax()).isLessThan(dayStart + 86_400L * 1_000_000_000L);
                });
            }
        }
    }

    @Test
    void quotesAndTradesShareAWriterEpoch() {
        long dayStart = Instant.parse("2026-07-27T00:00:00Z").getEpochSecond() * 1_000_000_000L;
        FixedNanoClock nanoClock = new FixedNanoClock(dayStart);
        try (MarketDataWriter writer = new MarketDataWriter(base, MarketDataGenerator.DEFAULT_SYMBOLS,
                256, 64, 1L, 4, fixedClock("2026-07-27T00:00:00Z"), nanoClock)) {
            for (int i = 0; i < 500; i++) {
                nanoClock.set(dayStart + i * 1_000_000L);
                writer.writeEvent(nanoClock.nanoTime());
            }
        }
        // One process, one epoch: readers can tell both tables came from the same writer instance.
        assertThat(new SegmentDirectory(base, "quotes").latestEpoch())
                .isEqualTo(new SegmentDirectory(base, "trades").latestEpoch());
    }

    private static long varlenBytes(ArenaSchema schema, String column) {
        return schema.columns().stream()
                .filter(c -> c.name().equals(column))
                .findFirst().orElseThrow()
                .varlenBytes().orElseThrow();
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static final class FixedNanoClock implements EpochNanoClock {
        private long nanos;

        FixedNanoClock(long nanos) {
            this.nanos = nanos;
        }

        void set(long nanos) {
            this.nanos = nanos;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }
    }
}
