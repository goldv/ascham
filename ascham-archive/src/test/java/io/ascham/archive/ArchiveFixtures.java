package io.ascham.archive;

import io.ascham.rotate.RollCycle;
import io.ascham.rotate.RotatingWriter;
import io.ascham.rotate.SegmentDirectory;
import io.ascham.schema.ArenaSchema;
import io.ascham.schema.MetadataKeys;
import io.ascham.write.Appender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/** Shared fixtures for the cold-tier tests: a quotes-shaped schema and a writer that fills days. */
final class ArchiveFixtures {

    static final String[] SYMBOLS = {"AAPL", "MSFT", "GOOG"};
    private static final long NANOS_PER_DAY = 86_400L * 1_000_000_000L;

    private ArchiveFixtures() {
    }

    /** A UTC clock the test drives, so day boundaries are deterministic. */
    static final class MutableClock extends Clock {
        private volatile Instant instant;

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
        public long millis() {
            return instant.toEpochMilli(); // allocation-free, like Clock.systemUTC()
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

    static EpochNanoClock counterNanoClock() {
        return new EpochNanoClock() {
            private long t = 1;

            @Override
            public long nanoTime() {
                return t++;
            }
        };
    }

    /** {@code (ts TIMESTAMP(ns,UTC), sym UTF8, px INT64)} with ts as time column, px as stats. */
    static ArenaSchema quotesSchema(int batchRows) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC"), Map.of()),
                field("sym", new ArrowType.Utf8(), Map.of(MetadataKeys.VARLEN_BYTES, "512")),
                field("px", new ArrowType.Int(64, true), Map.of()));
        return ArenaSchema.load(new Schema(fields, Map.of(
                MetadataKeys.TABLE, "quotes",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "px",
                MetadataKeys.BATCH_ROWS, Integer.toString(batchRows))));
    }

    /**
     * Writes {@code rowsPerDay} rows for each of {@code days} into {@code base/quotes}, rotating on
     * the UTC day boundary exactly as a live writer would, and sealing every segment on close.
     * Timestamps are spread through each day so zone maps are meaningful.
     */
    static void writeDays(Path base, List<LocalDate> days, int rowsPerDay) {
        MutableClock clock = new MutableClock(days.get(0).atStartOfDay(ZoneOffset.UTC).toInstant());
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        try (RotatingWriter writer = RotatingWriter.open(dir, quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, counterNanoClock())) {
            for (LocalDate day : days) {
                clock.set(day.atStartOfDay(ZoneOffset.UTC).toInstant());
                writer.heartbeat(); // rotates onto the new day (R2: rotation is checked here too)
                long dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
                // Spread evenly across the day so every row stays inside it whatever rowsPerDay is —
                // a fixed per-row step would run past midnight for large counts and (correctly) trip
                // the roller's day-alignment check. The jitter adds sub-microsecond digits, which is
                // what makes nanosecond fidelity actually testable.
                long step = NANOS_PER_DAY / (rowsPerDay + 1L);
                for (int r = 0; r < rowsPerDay; r++) {
                    long ts = dayStart + r * step + (r % 997) + 7;
                    append(writer, ts, SYMBOLS[r % SYMBOLS.length], 1_000_000L + r);
                }
            }
        }
    }

    /**
     * Writes {@code rowsPerInterval} rows for each interval start into {@code base/quotes} with the
     * given cycle, rotating on the interval boundary exactly as a live writer would, and sealing
     * every segment on close. Starts must be ascending and on the cycle grid.
     */
    static void writeIntervals(Path base, RollCycle cycle, List<Instant> starts, int rowsPerInterval) {
        MutableClock clock = new MutableClock(starts.get(0));
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        try (RotatingWriter writer = RotatingWriter.open(dir, quotesSchema(64), 4096, 1L,
                cycle, clock, counterNanoClock())) {
            for (Instant start : starts) {
                clock.set(start);
                writer.heartbeat(); // rotates onto the new interval
                long startNanos = start.getEpochSecond() * 1_000_000_000L;
                long step = cycle.duration().toNanos() / (rowsPerInterval + 1L);
                for (int r = 0; r < rowsPerInterval; r++) {
                    long ts = startNanos + r * step + (r % 997) + 7;
                    append(writer, ts, SYMBOLS[r % SYMBOLS.length], 1_000_000L + r);
                }
            }
        }
    }

    /** Appends one quotes row (tests, not hot path: the symbol buffer is built per call). */
    static void append(RotatingWriter writer, long ts, String sym, long px) {
        byte[] symBytes = sym.getBytes(StandardCharsets.UTF_8);
        UnsafeBuffer buf = new UnsafeBuffer(symBytes);
        Appender a = writer.appender();
        a.beginRow();
        a.setLong(0, ts);
        a.setBytes(1, buf, 0, symBytes.length);
        a.setLong(2, px);
        a.endRow();
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }
}
