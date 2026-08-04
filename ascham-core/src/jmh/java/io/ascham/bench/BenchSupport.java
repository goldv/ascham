package io.ascham.bench;

import io.ascham.schema.ArenaSchema;
import io.ascham.schema.MetadataKeys;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.EpochNanoClock;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/** Shared schema/clock helpers for the benchmarks (the jmh source set can't see test fixtures). */
final class BenchSupport {

    private BenchSupport() {
    }

    /** A monotonic clock with no syscall, so seal cost isn't dominated by the wall clock. */
    static EpochNanoClock counterClock() {
        return new EpochNanoClock() {
            private long t;

            @Override
            public long nanoTime() {
                return t++;
            }
        };
    }

    static ArenaSchema allTypes(int batchRows) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("flag", new ArrowType.Bool()),
                field("i8", new ArrowType.Int(8, true)),
                field("u16", new ArrowType.Int(16, false)),
                field("i32", new ArrowType.Int(32, true)),
                field("i64", new ArrowType.Int(64, true)),
                field("f32", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                field("f64", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                field("dec", new ArrowType.Decimal(38, 9, 128)),
                field("d32", new ArrowType.Date(DateUnit.DAY)),
                field("t64", new ArrowType.Time(TimeUnit.NANOSECOND, 64)),
                field("fsb", new ArrowType.FixedSizeBinary(16)),
                field("sym", new ArrowType.Utf8(), Map.of(MetadataKeys.VARLEN_BYTES, "65536")));
        return load(fields, "alltypes", batchRows);
    }

    static ArenaSchema tsStats(int batchRows) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)));
        return load(fields, "tsstats", batchRows);
    }

    private static ArenaSchema load(List<Field> fields, String table, int batchRows) {
        return ArenaSchema.load(new Schema(fields, Map.of(
                MetadataKeys.TABLE, table,
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "i64",
                MetadataKeys.BATCH_ROWS, Integer.toString(batchRows))));
    }

    private static Field field(String name, ArrowType type) {
        return field(name, type, Map.of());
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }
}
