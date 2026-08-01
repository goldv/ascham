package io.ito.arena.rotate;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.MetadataKeys;
import io.ito.arena.write.Appender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.EpochNanoClock;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

final class RotateFixtures {

    private RotateFixtures() {
    }

    /** A UTC clock whose instant can be advanced, for driving day-boundary rotation in tests. */
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
            private long t;

            @Override
            public long nanoTime() {
                return t++;
            }
        };
    }

    static ArenaSchema tsStats(int batchRows) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)));
        return ArenaSchema.load(new Schema(fields, Map.of(
                MetadataKeys.TABLE, "trades",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "i64",
                MetadataKeys.BATCH_ROWS, Integer.toString(batchRows))));
    }

    /**
     * {@code (ts, i64, sym[symBytes], bin[binBytes])} — two varlen columns with tight byte caps,
     * for driving varlen-exhaustion rotation.
     */
    static ArenaSchema varlen(int batchRows, int symBytes, int binBytes) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)),
                varlenField("sym", new ArrowType.Utf8(), symBytes),
                varlenField("bin", new ArrowType.Binary(), binBytes));
        return ArenaSchema.load(new Schema(fields, Map.of(
                MetadataKeys.TABLE, "varlen",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "i64",
                MetadataKeys.BATCH_ROWS, Integer.toString(batchRows))));
    }

    /** Appends one row setting ts and the stats column. */
    static void append(RotatingWriter writer, long ts, long stat) {
        Appender a = writer.appender();
        a.beginRow();
        a.setLong(0, ts);
        a.setLong(1, stat);
        a.endRow();
    }

    private static Field field(String name, ArrowType type) {
        return new Field(name, new FieldType(true, type, null, Map.of()), List.of());
    }

    private static Field varlenField(String name, ArrowType type, int varlenBytes) {
        return new Field(name, new FieldType(true, type, null,
                Map.of(MetadataKeys.VARLEN_BYTES, Integer.toString(varlenBytes))), List.of());
    }
}
