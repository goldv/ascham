package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * Maps an arena schema to the Iceberg table it rolls into, per docs/cold-tier-design-plan.md §7.
 *
 * <p>Two outputs per column, and they must agree: the DDL type of the historical column, and the
 * SELECT expression that reads it out of {@code arena_scan}. Where Iceberg has no matching type the
 * column widens and the SELECT carries an explicit CAST — Iceberg has no unsigned integers, so
 * {@code UINTEGER} becomes {@code BIGINT} and {@code UBIGINT} becomes {@code DECIMAL(20,0)} (the
 * narrowest exact type that holds 2^64-1). Nanosecond timestamps map straight through to Iceberg V3
 * {@code timestamp_ns}, verified lossless at R1.
 */
public final class TypeMapping {

    /** One column's historical form: its DDL type and how to read it from the arena. */
    public record Column(String name, String duckdbType, String selectExpression) {
    }

    private TypeMapping() {
    }

    public static List<Column> columns(ArenaSchema schema) {
        List<Column> out = new ArrayList<>(schema.columnCount());
        for (int i = 0; i < schema.columnCount(); i++) {
            out.add(column(schema.field(i)));
        }
        return out;
    }

    /** {@code "sym VARCHAR, ts TIMESTAMP_NS, px BIGINT"} — the CREATE TABLE column list. */
    public static String ddlColumnList(ArenaSchema schema) {
        List<String> parts = new ArrayList<>();
        for (Column c : columns(schema)) {
            parts.add(quote(c.name()) + " " + c.duckdbType());
        }
        return String.join(", ", parts);
    }

    /** The SELECT list, with widening casts and an alias per column so positions line up. */
    public static String selectList(ArenaSchema schema) {
        List<String> parts = new ArrayList<>();
        for (Column c : columns(schema)) {
            parts.add(c.selectExpression() + " AS " + quote(c.name()));
        }
        return String.join(", ", parts);
    }

    private static Column column(Field field) {
        String name = field.getName();
        String read = quote(name);
        ArrowType type = field.getType();
        return switch (type.getTypeID()) {
            case Bool -> new Column(name, "BOOLEAN", read);
            case Int -> intColumn(name, read, (ArrowType.Int) type);
            case FloatingPoint -> ((ArrowType.FloatingPoint) type).getPrecision() == FloatingPointPrecision.SINGLE
                    ? new Column(name, "FLOAT", read)
                    : new Column(name, "DOUBLE", read);
            case Decimal -> {
                ArrowType.Decimal d = (ArrowType.Decimal) type;
                yield new Column(name, "DECIMAL(" + d.getPrecision() + "," + d.getScale() + ")", read);
            }
            case Date -> new Column(name, "DATE", read);
            // arena_scan already surfaces TIME64_NS as DuckDB TIME (microseconds); Iceberg's `time`
            // is microseconds too, so nothing is lost here that the live query surface still shows.
            case Time -> new Column(name, "TIME", read);
            case Timestamp -> timestampColumn(name, read, (ArrowType.Timestamp) type);
            case Utf8 -> new Column(name, "VARCHAR", read);
            // Iceberg `binary`; the fixed width is not preserved (arena_scan already erases it).
            case Binary, FixedSizeBinary -> new Column(name, "BLOB", read);
            default -> throw new IllegalArgumentException(
                    "column '" + name + "': type has no cold-tier mapping: " + type);
        };
    }

    private static Column intColumn(String name, String read, ArrowType.Int type) {
        int bits = type.getBitWidth();
        if (type.getIsSigned()) {
            // Iceberg has only 32- and 64-bit integers, so i8/i16 widen. The value is unchanged, but
            // the cast keeps the SELECT's type identical to the column it is inserted into.
            return switch (bits) {
                case 8, 16, 32 -> new Column(name, "INTEGER", bits == 32 ? read : cast(read, "INTEGER"));
                case 64 -> new Column(name, "BIGINT", read);
                default -> throw new IllegalArgumentException("unsupported signed int width " + bits);
            };
        }
        // Unsigned: widen to the narrowest signed/exact type that cannot overflow.
        return switch (bits) {
            case 8, 16 -> new Column(name, "INTEGER", cast(read, "INTEGER"));
            case 32 -> new Column(name, "BIGINT", cast(read, "BIGINT"));
            case 64 -> new Column(name, "DECIMAL(20,0)", cast(read, "DECIMAL(20,0)"));
            default -> throw new IllegalArgumentException("unsupported unsigned int width " + bits);
        };
    }

    private static Column timestampColumn(String name, String read, ArrowType.Timestamp type) {
        return switch (type.getUnit()) {
            case NANOSECOND -> new Column(name, "TIMESTAMP_NS", read);
            case MICROSECOND -> type.getTimezone() == null || type.getTimezone().isEmpty()
                    ? new Column(name, "TIMESTAMP", read)
                    : new Column(name, "TIMESTAMPTZ", read);
            default -> throw new IllegalArgumentException(
                    "column '" + name + "': unsupported timestamp unit " + type.getUnit());
        };
    }

    private static String cast(String expr, String type) {
        return "CAST(" + expr + " AS " + type + ")";
    }

    /** Double-quotes an identifier so column names that collide with SQL keywords still work. */
    static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
