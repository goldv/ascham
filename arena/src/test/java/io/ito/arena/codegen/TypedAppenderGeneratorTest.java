package io.ito.arena.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.CanonicalSchema;
import io.ito.arena.schema.MetadataKeys;
import io.ito.arena.util.Sha256;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

class TypedAppenderGeneratorTest {

    @Test
    void generationIsDeterministic() {
        ArenaSchema schema = tradesSchema();
        assertThat(TypedAppenderGenerator.generate(schema, "io.ito.arena.gen", "TradesAppender"))
                .isEqualTo(TypedAppenderGenerator.generate(schema, "io.ito.arena.gen", "TradesAppender"));
    }

    @Test
    void emitsTypedSettersAndSchemaHash() {
        ArenaSchema schema = tradesSchema();
        String src = TypedAppenderGenerator.generate(schema, "io.ito.arena.gen", "TradesAppender");

        assertThat(src)
                .contains("package io.ito.arena.gen;")
                .contains("public final class TradesAppender extends RowAppender")
                .contains("public TradesAppender(SegmentWriter writer)")
                .contains("SCHEMA_SHA256 = \"" + Sha256.toHex(CanonicalSchema.sha256(schema)) + "\"")
                .contains("public void setTs(long value)")
                .contains("setLong(0, value)")
                .contains("public void setFlag(boolean value)")
                .contains("public void setPx(long value)")
                .contains("public void setQty(int value)")
                .contains("public void setRate(double value)")
                .contains("public void setDec(long low, long high)")
                .contains("setDecimal128(5, low, high)")
                .contains("public void setSym(DirectBuffer value, int offset, int length)")
                .contains("public void setSymNull()");
    }

    @Test
    void collidingNamesGetDistinctMethods() {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("a b", new ArrowType.Int(64, true)),
                field("a_b", new ArrowType.Int(64, true)));
        ArenaSchema schema = ArenaSchema.load(new Schema(fields, meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts")));

        String src = TypedAppenderGenerator.generate(schema, "io.ito.arena.gen", "T");
        // "a b" -> setAB (ordinal 1); "a_b" collides -> setAB2 (ordinal 2).
        assertThat(src).contains("public void setAB(long value)").contains("public void setAB2(long value)");
    }

    private static ArenaSchema tradesSchema() {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("flag", new ArrowType.Bool()),
                field("px", new ArrowType.Int(64, true)),
                field("qty", new ArrowType.Int(32, true)),
                field("rate", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                field("dec", new ArrowType.Decimal(38, 9, 128)),
                field("sym", new ArrowType.Utf8(), meta(MetadataKeys.VARLEN_BYTES, "1024")));
        return ArenaSchema.load(new Schema(fields, meta(
                MetadataKeys.TABLE, "trades",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "px")));
    }

    private static Field field(String name, ArrowType type) {
        return field(name, type, Map.of());
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }

    private static Map<String, String> meta(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
