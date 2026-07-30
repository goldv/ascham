package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.MetadataKeys;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

/** The arena → Iceberg type mapping of docs/cold-tier-design-plan.md §7. */
class TypeMappingTest {

    @Test
    void nanosecondTimestampsMapStraightThrough() {
        ArenaSchema schema = schemaOf(field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")));
        assertThat(TypeMapping.columns(schema)).singleElement()
                .satisfies(c -> {
                    assertThat(c.duckdbType()).isEqualTo("TIMESTAMP_NS");
                    assertThat(c.selectExpression()).isEqualTo("\"ts\""); // no cast: lossless (R1)
                });
    }

    @Test
    void unsignedIntegersWidenBecauseIcebergHasNone() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("u8", new ArrowType.Int(8, false)),
                field("u16", new ArrowType.Int(16, false)),
                field("u32", new ArrowType.Int(32, false)),
                field("u64", new ArrowType.Int(64, false)));

        assertThat(TypeMapping.columns(schema)).extracting(TypeMapping.Column::duckdbType)
                .containsExactly("TIMESTAMP_NS", "INTEGER", "INTEGER", "BIGINT", "DECIMAL(20,0)");
        // u64's full range (2^64-1) does not fit any signed integer, so it becomes an exact decimal.
        assertThat(TypeMapping.columns(schema).get(4).selectExpression())
                .isEqualTo("CAST(\"u64\" AS DECIMAL(20,0))");
    }

    @Test
    void narrowSignedIntegersWidenToIntegerButKeepTheirValues() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i8", new ArrowType.Int(8, true)),
                field("i32", new ArrowType.Int(32, true)),
                field("i64", new ArrowType.Int(64, true)));

        assertThat(TypeMapping.columns(schema)).extracting(TypeMapping.Column::duckdbType)
                .containsExactly("TIMESTAMP_NS", "INTEGER", "INTEGER", "BIGINT");
        assertThat(TypeMapping.columns(schema).get(1).selectExpression()).isEqualTo("CAST(\"i8\" AS INTEGER)");
        assertThat(TypeMapping.columns(schema).get(2).selectExpression()).isEqualTo("\"i32\"");
    }

    @Test
    void remainingV1TypesMapAsDocumented() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("b", new ArrowType.Bool()),
                field("f32", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                field("f64", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                field("dec", new ArrowType.Decimal(38, 9, 128)),
                field("d", new ArrowType.Date(DateUnit.DAY)),
                field("t", new ArrowType.Time(TimeUnit.NANOSECOND, 64)),
                field("s", new ArrowType.Utf8()),
                field("bin", new ArrowType.Binary()),
                field("fx", new ArrowType.FixedSizeBinary(16)));

        assertThat(TypeMapping.columns(schema)).extracting(TypeMapping.Column::duckdbType)
                .containsExactly("TIMESTAMP_NS", "BOOLEAN", "FLOAT", "DOUBLE", "DECIMAL(38,9)",
                        "DATE", "TIME", "VARCHAR", "BLOB", "BLOB");
    }

    @Test
    void ddlAndSelectListsAgreeColumnForColumn() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("sym", new ArrowType.Utf8()),
                field("qty", new ArrowType.Int(32, false)));

        assertThat(TypeMapping.ddlColumnList(schema))
                .isEqualTo("\"ts\" TIMESTAMP_NS, \"sym\" VARCHAR, \"qty\" BIGINT");
        // Every SELECT item is aliased, so INSERT ... SELECT lines up positionally and by name.
        assertThat(TypeMapping.selectList(schema))
                .isEqualTo("\"ts\" AS \"ts\", \"sym\" AS \"sym\", CAST(\"qty\" AS BIGINT) AS \"qty\"");
    }

    @Test
    void identifiersAreQuotedSoKeywordColumnNamesSurvive() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("order", new ArrowType.Int(64, true))); // a reserved word
        assertThat(TypeMapping.ddlColumnList(schema)).contains("\"order\" BIGINT");
    }

    @Test
    void unmappableTypesCannotEvenReachTheMapping() {
        // The arena's own schema validator is the gate: it enforces the v1 type profile at load, so
        // a type with no cold-tier mapping cannot be built into an ArenaSchema in the first place.
        // TypeMapping's own `default -> throw` stays as a backstop for future profile widening.
        assertThatThrownBy(() -> schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("odd", new ArrowType.Timestamp(TimeUnit.SECOND, null))))
                .hasMessageContaining("only Timestamp(NANOSECOND|MICROSECOND) is supported");
    }

    private static ArenaSchema schemaOf(Field... fields) {
        return ArenaSchema.load(new Schema(List.of(fields), Map.of(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.BATCH_ROWS, "64")));
    }

    private static Field field(String name, ArrowType type) {
        Map<String, String> md = type.getTypeID() == ArrowType.ArrowTypeID.Utf8
                || type.getTypeID() == ArrowType.ArrowTypeID.Binary
                ? Map.of(MetadataKeys.VARLEN_BYTES, "512")
                : Map.of();
        return new Field(name, new FieldType(true, type, null, md), List.of());
    }
}
