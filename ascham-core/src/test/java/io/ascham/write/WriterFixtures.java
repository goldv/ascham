package io.ascham.write;

import io.ascham.schema.ArenaSchema;
import io.ascham.schema.MetadataKeys;
import java.util.LinkedHashMap;
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

/** Shared builders for the writer tests. */
final class WriterFixtures {

    private WriterFixtures() {
    }

    /** Deterministic clock for reproducible {@code seal_nanos}. */
    static final class FakeClock implements EpochNanoClock {
        private final long start;
        private final long step;
        private long calls;

        FakeClock(long start, long step) {
            this.start = start;
            this.step = step;
        }

        @Override
        public long nanoTime() {
            long t = start + step * calls;
            calls++;
            return t;
        }
    }

    static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }

    static Field field(String name, ArrowType type) {
        return field(name, type, Map.of());
    }

    static Map<String, String> meta(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** One column per supported physical encoding; "ts" is the time column, "i64" the stats column. */
    static ArenaSchema allTypesSchema(int batchRows) {
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
                field("sym", new ArrowType.Utf8(), meta(MetadataKeys.VARLEN_BYTES, "4096")),
                field("bin", new ArrowType.Binary(), meta(MetadataKeys.VARLEN_BYTES, "4096")));
        Map<String, String> schemaMeta = meta(
                MetadataKeys.TABLE, "alltypes",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "i64",
                MetadataKeys.BATCH_ROWS, Integer.toString(batchRows));
        return ArenaSchema.load(new Schema(fields, schemaMeta));
    }

    /** A tiny schema with one varlen column of the given byte capacity, for exhaustion tests. */
    static ArenaSchema varlenSchema(int batchRows, int varlenBytes) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)),
                field("sym", new ArrowType.Utf8(), meta(MetadataKeys.VARLEN_BYTES, Integer.toString(varlenBytes))));
        Map<String, String> schemaMeta = meta(
                MetadataKeys.TABLE, "varlen",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "i64",
                MetadataKeys.BATCH_ROWS, Integer.toString(batchRows));
        return ArenaSchema.load(new Schema(fields, schemaMeta));
    }
}
