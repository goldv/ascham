package io.ascham.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Builders for Arrow {@link Field}s and {@link Schema}s in tests. Keeps the verbose Arrow pojo
 * construction out of the test bodies.
 */
final class SchemaFixtures {

    private SchemaFixtures() {
    }

    static Field field(String name, ArrowType type, Map<String, String> metadata) {
        FieldType ft = new FieldType(true, type, null, metadata);
        return new Field(name, ft, List.of());
    }

    static Field field(String name, ArrowType type) {
        return field(name, type, Map.of());
    }

    static Field dictField(String name, ArrowType type) {
        DictionaryEncoding enc = new DictionaryEncoding(0L, false, new ArrowType.Int(32, true));
        return new Field(name, new FieldType(true, type, enc, Map.of()), List.of());
    }

    static ArrowType tsNanos() {
        return new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC");
    }

    static ArrowType tsMicros() {
        return new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
    }

    static ArrowType int32() {
        return new ArrowType.Int(32, true);
    }

    static ArrowType int64() {
        return new ArrowType.Int(64, true);
    }

    static ArrowType decimal128() {
        return new ArrowType.Decimal(38, 9, 128);
    }

    static ArrowType date32() {
        return new ArrowType.Date(DateUnit.DAY);
    }

    static ArrowType time64ns() {
        return new ArrowType.Time(TimeUnit.NANOSECOND, 64);
    }

    static ArrowType float64() {
        return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    }

    static Map<String, String> meta(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("meta() needs key/value pairs");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A minimal valid schema: a timestamp time column, an int64 stats column, and a Utf8 column. */
    static Schema validSchema() {
        List<Field> fields = new ArrayList<>();
        fields.add(field("ts", tsNanos()));
        fields.add(field("px", int64()));
        fields.add(field("sym", new ArrowType.Utf8(), meta(MetadataKeys.VARLEN_BYTES, "4096")));
        Map<String, String> schemaMeta = meta(
                MetadataKeys.TABLE, "trades",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "px");
        return new Schema(fields, schemaMeta);
    }
}
