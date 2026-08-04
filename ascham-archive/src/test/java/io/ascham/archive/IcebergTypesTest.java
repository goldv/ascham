package io.ascham.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ascham.schema.ArenaSchema;
import io.ascham.schema.MetadataKeys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/** The arena → Iceberg type mapping of docs/cold-tier-design-plan.md §7, natively. */
class IcebergTypesTest {

    @Test
    void nanosecondTimestampsMapStraightThrough() {
        ArenaSchema schema = schemaOf(field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")));
        assertThat(types(schema)).containsExactly(Types.TimestampNanoType.withoutZone());
    }

    @Test
    void fieldIdsAreOrdinalPlusOneInSchemaOrder() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("sym", new ArrowType.Utf8()),
                field("px", new ArrowType.Int(64, true)));
        org.apache.iceberg.Schema iceberg = IcebergTypes.icebergSchema(schema);
        assertThat(iceberg.columns()).extracting(Types.NestedField::fieldId).containsExactly(1, 2, 3);
        assertThat(iceberg.columns()).extracting(Types.NestedField::name).containsExactly("ts", "sym", "px");
        assertThat(iceberg.columns()).allSatisfy(f -> assertThat(f.isOptional()).isTrue());
    }

    @Test
    void unsignedIntegersWidenBecauseIcebergHasNone() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("u8", new ArrowType.Int(8, false)),
                field("u16", new ArrowType.Int(16, false)),
                field("u32", new ArrowType.Int(32, false)),
                field("u64", new ArrowType.Int(64, false)));

        assertThat(types(schema)).containsExactly(
                Types.TimestampNanoType.withoutZone(),
                Types.IntegerType.get(),
                Types.IntegerType.get(),
                Types.LongType.get(),
                // u64's full range (2^64-1) does not fit any signed integer, so exact decimal.
                Types.DecimalType.of(20, 0));
    }

    @Test
    void unsignedCopiersWidenTopOfRangeValuesExactly() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("u8", new ArrowType.Int(8, false)),
                field("u16", new ArrowType.Int(16, false)),
                field("u32", new ArrowType.Int(32, false)),
                field("u64", new ArrowType.Int(64, false)));
        IcebergTypes.ColumnCopier[] copiers = IcebergTypes.copiers(schema);

        try (BufferAllocator alloc = new RootAllocator();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema.arrowSchema(), alloc)) {
            root.allocateNew();
            ((TimeStampNanoTZVector) root.getVector(0)).setSafe(0, 1L);
            ((UInt1Vector) root.getVector(1)).setSafe(0, 0xFF);
            ((UInt2Vector) root.getVector(2)).setSafe(0, 0xFFFF);
            ((UInt4Vector) root.getVector(3)).setSafe(0, 0xFFFFFFFF);
            ((UInt8Vector) root.getVector(4)).setSafe(0, -1L); // bit pattern of 2^64-1
            root.setRowCount(1);

            assertThat(copiers[1].read(root, 0)).isEqualTo(255);
            assertThat(copiers[2].read(root, 0)).isEqualTo(65535);
            assertThat(copiers[3].read(root, 0)).isEqualTo(4294967295L);
            assertThat(copiers[4].read(root, 0)).isEqualTo(new BigDecimal("18446744073709551615"));
        }
    }

    @Test
    void nanosecondCopierKeepsFullPrecisionAndNullsStayNull() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("sym", new ArrowType.Utf8()));
        IcebergTypes.ColumnCopier[] copiers = IcebergTypes.copiers(schema);
        long nanos = 1_753_999_200_123_456_789L; // sub-microsecond digits must survive

        try (BufferAllocator alloc = new RootAllocator();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema.arrowSchema(), alloc)) {
            root.allocateNew();
            ((TimeStampNanoTZVector) root.getVector(0)).setSafe(0, nanos);
            ((VarCharVector) root.getVector(1)).setSafe(0, "AAPL".getBytes(StandardCharsets.UTF_8));
            ((TimeStampNanoTZVector) root.getVector(0)).setNull(1);
            ((VarCharVector) root.getVector(1)).setNull(1);
            root.setRowCount(2);

            java.time.LocalDateTime ts = (java.time.LocalDateTime) copiers[0].read(root, 0);
            assertThat(ts.getNano() % 1_000_000).isEqualTo(456_789); // nanos below the milli survive
            assertThat(org.apache.iceberg.util.DateTimeUtil.nanosFromTimestamp(ts)).isEqualTo(nanos);
            assertThat(copiers[1].read(root, 0)).isEqualTo("AAPL");
            assertThat(copiers[0].read(root, 1)).isNull();
            assertThat(copiers[1].read(root, 1)).isNull();
        }
    }

    @Test
    void narrowSignedIntegersWidenToIntegerButKeepTheirValues() {
        ArenaSchema schema = schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i8", new ArrowType.Int(8, true)),
                field("i32", new ArrowType.Int(32, true)),
                field("i64", new ArrowType.Int(64, true)));

        assertThat(types(schema)).containsExactly(
                Types.TimestampNanoType.withoutZone(),
                Types.IntegerType.get(),
                Types.IntegerType.get(),
                Types.LongType.get());
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

        assertThat(types(schema)).containsExactly(
                Types.TimestampNanoType.withoutZone(),
                Types.BooleanType.get(),
                Types.FloatType.get(),
                Types.DoubleType.get(),
                Types.DecimalType.of(38, 9),
                Types.DateType.get(),
                Types.TimeType.get(),
                Types.StringType.get(),
                Types.BinaryType.get(),
                Types.BinaryType.get());
    }

    @Test
    void unmappableTypesCannotEvenReachTheMapping() {
        // The arena's own schema validator is the gate: it enforces the v1 type profile at load, so
        // a type with no cold-tier mapping cannot be built into an ArenaSchema in the first place.
        // IcebergTypes' own `default -> throw` stays as a backstop for future profile widening.
        assertThatThrownBy(() -> schemaOf(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("odd", new ArrowType.Timestamp(TimeUnit.SECOND, null))))
                .hasMessageContaining("only Timestamp(NANOSECOND|MICROSECOND) is supported");
    }

    private static List<Type> types(ArenaSchema schema) {
        return IcebergTypes.icebergSchema(schema).columns().stream()
                .map(f -> (Type) f.type()).toList();
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
