package io.ascham.schema;

import io.ascham.layout.PhysicalKind;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * The v1 supported-type whitelist. Accepts exactly the profile in {@code spec/ingest-arena.md}:
 *
 * <p>{@code Bool}, {@code Int8/16/32/64}, {@code UInt8/16/32/64}, {@code Float32}, {@code Float64},
 * {@code Decimal128(p,s)}, {@code Date32}, {@code Time64(ns)}, {@code Timestamp(ns|us, tz)},
 * {@code FixedSizeBinary(n)}, {@code Utf8}, {@code Binary}.
 *
 * <p>Everything else is rejected at load with a clear error. Two rejections are deliberate design
 * decisions, not omissions (see the class javadoc of {@link SchemaValidator} and the spec):
 * nested types (hand-rolled nested layout is where correctness bugs live, and the C++ reader must
 * be implementable without an Arrow dependency) and dictionary encoding (Arrow's dictionary index
 * is positional and stream-local; low-cardinality identifiers are plain {@code Int32} codes
 * resolved against a separate ref-data table).
 */
public final class TypeProfile {

    private TypeProfile() {
    }

    /**
     * Classifies an accepted field into its physical storage kind, or throws
     * {@link UnsupportedTypeException} with a specific reason if the type is outside the v1 profile.
     */
    public static PhysicalKind classify(Field field) {
        if (field.getFieldType().getDictionary() != null) {
            throw new UnsupportedTypeException(field.getName(),
                    "dictionary encoding is rejected in v1; store low-cardinality ids as Int32 codes "
                            + "with ascham.ref pointing at a ref-data table");
        }
        ArrowType type = field.getType();
        return switch (type.getTypeID()) {
            case Bool -> PhysicalKind.BOOL_BITMAP;
            case Int -> classifyInt((ArrowType.Int) type, field);
            case FloatingPoint -> classifyFloat((ArrowType.FloatingPoint) type, field);
            case Decimal -> classifyDecimal((ArrowType.Decimal) type, field);
            case Date -> classifyDate((ArrowType.Date) type, field);
            case Time -> classifyTime((ArrowType.Time) type, field);
            case Timestamp -> classifyTimestamp((ArrowType.Timestamp) type, field);
            case FixedSizeBinary -> PhysicalKind.FIXED;
            case Utf8, Binary -> PhysicalKind.VARLEN;
            default -> throw reject(field, type);
        };
    }

    /** Byte width of a FIXED column's element. Never called for VARLEN or BOOL_BITMAP. */
    public static int fixedWidthBytes(Field field) {
        ArrowType type = field.getType();
        return switch (type.getTypeID()) {
            case Int -> ((ArrowType.Int) type).getBitWidth() / 8;
            case FloatingPoint -> switch (((ArrowType.FloatingPoint) type).getPrecision()) {
                case SINGLE -> 4;
                case DOUBLE -> 8;
                default -> throw reject(field, type);
            };
            case Decimal -> ((ArrowType.Decimal) type).getBitWidth() / 8;
            case Date -> 4;
            case Time -> ((ArrowType.Time) type).getBitWidth() / 8;
            case Timestamp -> 8;
            case FixedSizeBinary -> ((ArrowType.FixedSizeBinary) type).getByteWidth();
            default -> throw new IllegalArgumentException(
                    "not a fixed-width type: " + field.getName() + " (" + type + ")");
        };
    }

    private static PhysicalKind classifyInt(ArrowType.Int type, Field field) {
        int bits = type.getBitWidth();
        if (bits != 8 && bits != 16 && bits != 32 && bits != 64) {
            throw new UnsupportedTypeException(field.getName(),
                    "only 8/16/32/64-bit integers are supported, got " + bits + "-bit");
        }
        return PhysicalKind.FIXED;
    }

    private static PhysicalKind classifyFloat(ArrowType.FloatingPoint type, Field field) {
        if (type.getPrecision() == FloatingPointPrecision.HALF) {
            throw new UnsupportedTypeException(field.getName(), "Float16 (half precision) is rejected in v1");
        }
        return PhysicalKind.FIXED;
    }

    private static PhysicalKind classifyDecimal(ArrowType.Decimal type, Field field) {
        if (type.getBitWidth() != 128) {
            throw new UnsupportedTypeException(field.getName(),
                    "only Decimal128 is supported, got Decimal" + type.getBitWidth());
        }
        return PhysicalKind.FIXED;
    }

    private static PhysicalKind classifyDate(ArrowType.Date type, Field field) {
        if (type.getUnit() != DateUnit.DAY) {
            throw new UnsupportedTypeException(field.getName(),
                    "only Date32 (DAY unit) is supported, got " + type.getUnit());
        }
        return PhysicalKind.FIXED;
    }

    private static PhysicalKind classifyTime(ArrowType.Time type, Field field) {
        if (type.getUnit() != TimeUnit.NANOSECOND || type.getBitWidth() != 64) {
            throw new UnsupportedTypeException(field.getName(),
                    "only Time64(NANOSECOND) is supported, got " + type.getUnit() + "/" + type.getBitWidth());
        }
        return PhysicalKind.FIXED;
    }

    private static PhysicalKind classifyTimestamp(ArrowType.Timestamp type, Field field) {
        if (type.getUnit() != TimeUnit.NANOSECOND && type.getUnit() != TimeUnit.MICROSECOND) {
            throw new UnsupportedTypeException(field.getName(),
                    "only Timestamp(NANOSECOND|MICROSECOND) is supported, got " + type.getUnit());
        }
        return PhysicalKind.FIXED;
    }

    private static UnsupportedTypeException reject(Field field, ArrowType type) {
        return new UnsupportedTypeException(field.getName(),
                "type " + type.getTypeID() + " is outside the v1 profile"
                        + " (nested types and Union/Map/Interval/Duration/Null/LargeUtf8/LargeBinary are rejected)");
    }
}
