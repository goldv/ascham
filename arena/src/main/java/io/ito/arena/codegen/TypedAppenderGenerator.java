package io.ito.arena.codegen;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.CanonicalSchema;
import io.ito.arena.util.Sha256;
import java.util.HashSet;
import java.util.Set;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * Generates the Java source of a typed appender from a schema. Output is deterministic — no
 * timestamps or environment — so it is snapshot-testable and stable in version control. The
 * generated class extends {@code io.ito.arena.write.RowAppender} and exposes one named,
 * primitive-typed setter per column (plus a {@code ...Null()} method), each calling a protected
 * forwarder with the column's baked ordinal. Setters take primitives and {@code DirectBuffer}
 * slices only — never {@code String} or boxed types (spec: allocation-free hot path).
 *
 * <p>Build-time source generation, not runtime bytecode: a typed appender is only useful to a
 * producer that already knows the schema at its own compile time; truly-runtime schemas use the
 * {@code GenericAppender}. See docs/arena-design-plan.md §4b.
 */
public final class TypedAppenderGenerator {

    private TypedAppenderGenerator() {
    }

    public static String generate(ArenaSchema schema, String packageName, String className) {
        String hashHex = Sha256.toHex(CanonicalSchema.sha256(schema));
        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import io.ito.arena.write.RowAppender;\n");
        sb.append("import io.ito.arena.write.SegmentWriter;\n");
        sb.append("import org.agrona.DirectBuffer;\n\n");
        sb.append("/**\n");
        sb.append(" * Generated typed appender for table '").append(schema.metadata().table()).append("'.\n");
        sb.append(" * DO NOT EDIT — regenerate from the schema. Deterministic (no timestamps/environment).\n");
        sb.append(" */\n");
        sb.append("public final class ").append(className).append(" extends RowAppender {\n\n");
        sb.append("    public static final String SCHEMA_SHA256 = \"").append(hashHex).append("\";\n\n");
        sb.append("    public ").append(className).append("(SegmentWriter writer) {\n");
        sb.append("        super(writer, SCHEMA_SHA256);\n");
        sb.append("    }\n");

        Set<String> used = new HashSet<>();
        for (int ordinal = 0; ordinal < schema.columnCount(); ordinal++) {
            Field field = schema.field(ordinal);
            String method = uniqueMethodName(field.getName(), ordinal, used);
            appendSetter(sb, method, ordinal, field);
            sb.append("\n    public void ").append(method).append("Null() {\n");
            sb.append("        setNull(").append(ordinal).append(");\n    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendSetter(StringBuilder sb, String method, int ordinal, Field field) {
        ArrowType type = field.getType();
        switch (type.getTypeID()) {
            case Bool -> forward(sb, method, "boolean value", "setBool", ordinal, "value");
            case Int -> {
                int bits = ((ArrowType.Int) type).getBitWidth();
                switch (bits) {
                    case 8 -> forward(sb, method, "byte value", "setByte", ordinal, "value");
                    case 16 -> forward(sb, method, "short value", "setShort", ordinal, "value");
                    case 32 -> forward(sb, method, "int value", "setInt", ordinal, "value");
                    default -> forward(sb, method, "long value", "setLong", ordinal, "value");
                }
            }
            case FloatingPoint -> {
                if (((ArrowType.FloatingPoint) type).getPrecision() == FloatingPointPrecision.SINGLE) {
                    forward(sb, method, "float value", "setFloat", ordinal, "value");
                } else {
                    forward(sb, method, "double value", "setDouble", ordinal, "value");
                }
            }
            case Decimal -> forward(sb, method, "long low, long high", "setDecimal128", ordinal, "low, high");
            case Date -> forward(sb, method, "int value", "setInt", ordinal, "value");     // Date32: days
            case Time -> forward(sb, method, "long value", "setLong", ordinal, "value");   // Time64(ns)
            case Timestamp -> forward(sb, method, "long value", "setLong", ordinal, "value");
            case FixedSizeBinary ->
                    forward(sb, method, "DirectBuffer value, int offset, int length",
                            "setFixedBytes", ordinal, "value, offset, length");
            case Utf8, Binary ->
                    forward(sb, method, "DirectBuffer value, int offset, int length",
                            "setBytes", ordinal, "value, offset, length");
            default -> throw new IllegalStateException("unsupported type reached codegen: " + type);
        }
    }

    private static void forward(StringBuilder sb, String method, String params,
                                String target, int ordinal, String args) {
        sb.append("\n    public void ").append(method).append('(').append(params).append(") {\n");
        sb.append("        ").append(target).append('(').append(ordinal).append(", ").append(args).append(");\n");
        sb.append("    }\n");
    }

    private static String uniqueMethodName(String column, int ordinal, Set<String> used) {
        String base = "set" + pascalCase(column);
        String name = used.add(base) ? base : base + ordinal;
        used.add(name);
        return name;
    }

    private static String pascalCase(String s) {
        StringBuilder b = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        return b.isEmpty() ? "Col" : b.toString();
    }
}
