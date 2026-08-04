package io.ascham.archive;

import io.ascham.schema.ArenaSchema;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeNanoVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.DateTimeUtil;

/**
 * Maps an arena schema to the Iceberg table it rolls into, per docs/cold-tier-design-plan.md §7.
 *
 * <p>Two outputs per column, and they must agree: the Iceberg field type of the historical column,
 * and the {@link ColumnCopier} that reads its value out of an arena batch. Where Iceberg has no
 * matching type the column widens and the copier carries the conversion — Iceberg has no unsigned
 * integers, so {@code u32} becomes {@code long} and {@code u64} becomes {@code decimal(20,0)} (the
 * narrowest exact type that holds 2^64-1). Nanosecond timestamps map straight through to Iceberg V3
 * {@code timestamp_ns}, verified lossless at R1.
 */
public final class IcebergTypes {

    /** Reads one column's value from a zero-copy batch root, in the Java form the Iceberg generic
     *  writer expects for the mapped field type. Returns null for null cells. */
    public interface ColumnCopier {
        Object read(VectorSchemaRoot root, int row);
    }

    private IcebergTypes() {
    }

    /** The Iceberg table schema, field ids 1..n in arena column order, all fields optional. */
    public static Schema icebergSchema(ArenaSchema schema) {
        List<Types.NestedField> fields = new ArrayList<>(schema.columnCount());
        for (int i = 0; i < schema.columnCount(); i++) {
            Field field = schema.field(i);
            fields.add(Types.NestedField.optional(i + 1, field.getName(), icebergType(field)));
        }
        return new Schema(fields);
    }

    /** One copier per column, in schema order. Each pairs with the type {@link #icebergSchema}
     *  declared for that column — the writer trusts this agreement. */
    public static ColumnCopier[] copiers(ArenaSchema schema) {
        ColumnCopier[] out = new ColumnCopier[schema.columnCount()];
        for (int i = 0; i < schema.columnCount(); i++) {
            out[i] = copier(schema.field(i), i);
        }
        return out;
    }

    public static Type icebergType(Field field) {
        ArrowType type = field.getType();
        return switch (type.getTypeID()) {
            case Bool -> Types.BooleanType.get();
            case Int -> intType(field.getName(), (ArrowType.Int) type);
            case FloatingPoint -> ((ArrowType.FloatingPoint) type).getPrecision() == FloatingPointPrecision.SINGLE
                    ? Types.FloatType.get()
                    : Types.DoubleType.get();
            case Decimal -> {
                ArrowType.Decimal d = (ArrowType.Decimal) type;
                yield Types.DecimalType.of(d.getPrecision(), d.getScale());
            }
            case Date -> Types.DateType.get();
            // Iceberg `time` is microseconds; the nanosecond arena time truncates on copy. The live
            // query surface shows the same truncation, so nothing rolls colder than it queries.
            case Time -> Types.TimeType.get();
            case Timestamp -> timestampType(field.getName(), (ArrowType.Timestamp) type);
            case Utf8 -> Types.StringType.get();
            // Iceberg `binary`; the fixed width is not preserved (parity with the BLOB mapping the
            // DuckDB roller used — FixedType(n) is a possible later upgrade).
            case Binary, FixedSizeBinary -> Types.BinaryType.get();
            default -> throw new IllegalArgumentException(
                    "column '" + field.getName() + "': type has no cold-tier mapping: " + type);
        };
    }

    private static Type intType(String name, ArrowType.Int type) {
        int bits = type.getBitWidth();
        if (type.getIsSigned()) {
            return switch (bits) {
                // Iceberg has only 32- and 64-bit integers, so i8/i16 widen; values are unchanged.
                case 8, 16, 32 -> Types.IntegerType.get();
                case 64 -> Types.LongType.get();
                default -> throw new IllegalArgumentException(
                        "column '" + name + "': unsupported signed int width " + bits);
            };
        }
        // Unsigned: widen to the narrowest signed/exact type that cannot overflow.
        return switch (bits) {
            case 8, 16 -> Types.IntegerType.get();
            case 32 -> Types.LongType.get();
            case 64 -> Types.DecimalType.of(20, 0);
            default -> throw new IllegalArgumentException(
                    "column '" + name + "': unsupported unsigned int width " + bits);
        };
    }

    private static Type timestampType(String name, ArrowType.Timestamp type) {
        boolean zoned = type.getTimezone() != null && !type.getTimezone().isEmpty();
        return switch (type.getUnit()) {
            // Deliberately without zone even for tz-carrying arena columns: DuckDB has no zoned
            // nanosecond type, so its iceberg extension cannot read `timestamptz_ns` — and the
            // query surface must be able to read what the roll writes. Values are UTC by
            // convention, exactly as the DuckDB roller's TIMESTAMP_NS was. Revisit if duckdb-
            // iceberg grows timestamptz_ns support.
            case NANOSECOND -> Types.TimestampNanoType.withoutZone();
            case MICROSECOND -> zoned
                    ? Types.TimestampType.withZone()
                    : Types.TimestampType.withoutZone();
            default -> throw new IllegalArgumentException(
                    "column '" + name + "': unsupported timestamp unit " + type.getUnit());
        };
    }

    private static ColumnCopier copier(Field field, int ordinal) {
        ArrowType type = field.getType();
        return switch (type.getTypeID()) {
            case Bool -> (root, row) -> {
                BitVector v = (BitVector) root.getVector(ordinal);
                return v.isNull(row) ? null : v.get(row) != 0;
            };
            case Int -> intCopier(ordinal, (ArrowType.Int) type);
            case FloatingPoint -> ((ArrowType.FloatingPoint) type).getPrecision() == FloatingPointPrecision.SINGLE
                    ? (root, row) -> {
                        Float4Vector v = (Float4Vector) root.getVector(ordinal);
                        return v.isNull(row) ? null : v.get(row);
                    }
                    : (root, row) -> {
                        Float8Vector v = (Float8Vector) root.getVector(ordinal);
                        return v.isNull(row) ? null : v.get(row);
                    };
            case Decimal -> (root, row) -> {
                DecimalVector v = (DecimalVector) root.getVector(ordinal);
                return v.isNull(row) ? null : v.getObject(row);
            };
            case Date -> (root, row) -> {
                DateDayVector v = (DateDayVector) root.getVector(ordinal);
                return v.isNull(row) ? null : LocalDate.ofEpochDay(v.get(row));
            };
            case Time -> (root, row) -> {
                TimeNanoVector v = (TimeNanoVector) root.getVector(ordinal);
                // Truncate to whole microseconds: Iceberg time is micros.
                return v.isNull(row) ? null : LocalTime.ofNanoOfDay(v.get(row) / 1_000 * 1_000);
            };
            case Timestamp -> timestampCopier(ordinal, (ArrowType.Timestamp) type);
            case Utf8 -> (root, row) -> {
                VarCharVector v = (VarCharVector) root.getVector(ordinal);
                return v.isNull(row) ? null : new String(v.get(row), java.nio.charset.StandardCharsets.UTF_8);
            };
            case Binary -> (root, row) -> {
                VarBinaryVector v = (VarBinaryVector) root.getVector(ordinal);
                return v.isNull(row) ? null : ByteBuffer.wrap(v.get(row));
            };
            case FixedSizeBinary -> (root, row) -> {
                FixedSizeBinaryVector v = (FixedSizeBinaryVector) root.getVector(ordinal);
                return v.isNull(row) ? null : ByteBuffer.wrap(v.get(row));
            };
            default -> throw new IllegalArgumentException(
                    "column '" + field.getName() + "': type has no cold-tier mapping: " + type);
        };
    }

    private static ColumnCopier intCopier(int ordinal, ArrowType.Int type) {
        int bits = type.getBitWidth();
        if (type.getIsSigned()) {
            return switch (bits) {
                case 8 -> (root, row) -> {
                    TinyIntVector v = (TinyIntVector) root.getVector(ordinal);
                    return v.isNull(row) ? null : (int) v.get(row);
                };
                case 16 -> (root, row) -> {
                    SmallIntVector v = (SmallIntVector) root.getVector(ordinal);
                    return v.isNull(row) ? null : (int) v.get(row);
                };
                case 32 -> (root, row) -> {
                    IntVector v = (IntVector) root.getVector(ordinal);
                    return v.isNull(row) ? null : v.get(row);
                };
                case 64 -> (root, row) -> {
                    BigIntVector v = (BigIntVector) root.getVector(ordinal);
                    return v.isNull(row) ? null : v.get(row);
                };
                default -> throw new IllegalArgumentException("unsupported signed int width " + bits);
            };
        }
        return switch (bits) {
            case 8 -> (root, row) -> {
                UInt1Vector v = (UInt1Vector) root.getVector(ordinal);
                return v.isNull(row) ? null : Byte.toUnsignedInt(v.get(row));
            };
            case 16 -> (root, row) -> {
                UInt2Vector v = (UInt2Vector) root.getVector(ordinal);
                // Arrow surfaces u16 as char: already the unsigned 0..65535 value.
                return v.isNull(row) ? null : (int) v.get(row);
            };
            case 32 -> (root, row) -> {
                UInt4Vector v = (UInt4Vector) root.getVector(ordinal);
                return v.isNull(row) ? null : Integer.toUnsignedLong(v.get(row));
            };
            case 64 -> (root, row) -> {
                UInt8Vector v = (UInt8Vector) root.getVector(ordinal);
                // 2^64-1 fits no signed long; decimal(20,0) is the narrowest exact home.
                return v.isNull(row) ? null : new BigDecimal(Long.toUnsignedString(v.get(row)));
            };
            default -> throw new IllegalArgumentException("unsupported unsigned int width " + bits);
        };
    }

    private static ColumnCopier timestampCopier(int ordinal, ArrowType.Timestamp type) {
        boolean zoned = type.getTimezone() != null && !type.getTimezone().isEmpty();
        return switch (type.getUnit()) {
            // TimeStampVector covers both the TZ and naive nano vectors; the target type is the
            // unzoned timestamp_ns either way (see timestampType).
            case NANOSECOND -> (root, row) -> {
                TimeStampVector v = (TimeStampVector) root.getVector(ordinal);
                return v.isNull(row) ? null : DateTimeUtil.timestampFromNanos(v.get(row));
            };
            case MICROSECOND -> zoned
                    ? (root, row) -> {
                        TimeStampMicroTZVector v = (TimeStampMicroTZVector) root.getVector(ordinal);
                        return v.isNull(row) ? null : DateTimeUtil.timestamptzFromMicros(v.get(row));
                    }
                    : (root, row) -> {
                        TimeStampMicroVector v = (TimeStampMicroVector) root.getVector(ordinal);
                        return v.isNull(row) ? null : DateTimeUtil.timestampFromMicros(v.get(row));
                    };
            default -> throw new IllegalArgumentException("unsupported timestamp unit " + type.getUnit());
        };
    }
}
