package io.ascham.conformance;

import io.ascham.read.BatchView;
import io.ascham.read.SnapshotReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.StringJoiner;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeNanoVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * Renders a golden segment's rows as language-neutral RFC 4180 CSV — the value side of the
 * cross-language contract (the {@code .bin} files pin bytes; these pin what the bytes mean). Any
 * reader in any language must decode the golden segments to exactly these values.
 *
 * <p>Rendering rules (kept deliberately locale- and precision-free):
 * <ul>
 *   <li>header row of column names; one data row per appended row, all batches in order,
 *       in-progress batches included</li>
 *   <li>NULL is an empty unquoted field; an empty string/binary is {@code ""} (quoted)</li>
 *   <li>{@code Bool} → {@code true}/{@code false}; integers (signed and unsigned) → decimal</li>
 *   <li>{@code Float32}/{@code Float64} → Java shortest round-trip form ({@code Float.toString})</li>
 *   <li>{@code Decimal128} → plain string at the declared scale</li>
 *   <li>{@code Date32} → days since epoch; {@code Time64(ns)} → nanoseconds;
 *       {@code Timestamp} → epoch count in the schema-declared unit</li>
 *   <li>{@code FixedSizeBinary}/{@code Binary} → lowercase hex; {@code Utf8} → quoted text with
 *       internal quotes doubled</li>
 * </ul>
 */
final class CsvExpected {

    private CsvExpected() {
    }

    static String render(Path segment) {
        StringBuilder out = new StringBuilder();
        try (SnapshotReader reader = SnapshotReader.open(segment)) {
            StringJoiner header = new StringJoiner(",");
            reader.schema().fields().forEach(f -> header.add(f.getName()));
            out.append(header).append('\n');
            for (BatchView batch : reader.snapshot().batches()) {
                try (VectorSchemaRoot root = batch.root()) {
                    for (int row = 0; row < batch.rowCount(); row++) {
                        StringJoiner line = new StringJoiner(",");
                        for (FieldVector vector : root.getFieldVectors()) {
                            line.add(cell(vector, row));
                        }
                        out.append(line).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private static String cell(FieldVector v, int row) {
        if (v.isNull(row)) {
            return "";
        }
        if (v instanceof BitVector bit) {
            return bit.get(row) == 1 ? "true" : "false";
        }
        if (v instanceof TinyIntVector t) {
            return Byte.toString(t.get(row));
        }
        if (v instanceof SmallIntVector s) {
            return Short.toString(s.get(row));
        }
        if (v instanceof IntVector i) {
            return Integer.toString(i.get(row));
        }
        if (v instanceof BigIntVector b) {
            return Long.toString(b.get(row));
        }
        if (v instanceof UInt1Vector u) {
            return Integer.toString(Byte.toUnsignedInt(u.get(row)));
        }
        if (v instanceof UInt2Vector u) {
            return Integer.toString(u.get(row));
        }
        if (v instanceof UInt4Vector u) {
            return Long.toString(Integer.toUnsignedLong(u.get(row)));
        }
        if (v instanceof UInt8Vector u) {
            return Long.toUnsignedString(u.get(row));
        }
        if (v instanceof Float4Vector f) {
            return Float.toString(f.get(row));
        }
        if (v instanceof Float8Vector f) {
            return Double.toString(f.get(row));
        }
        if (v instanceof DecimalVector d) {
            return d.getObject(row).toPlainString();
        }
        if (v instanceof DateDayVector d) {
            return Integer.toString(d.get(row));
        }
        if (v instanceof TimeNanoVector t) {
            return Long.toString(t.get(row));
        }
        if (v instanceof TimeStampVector t) {
            return Long.toString(t.get(row));
        }
        if (v instanceof FixedSizeBinaryVector f) {
            return hex(f.get(row));
        }
        if (v instanceof VarBinaryVector b) {
            return hex(b.get(row));
        }
        if (v instanceof VarCharVector c) {
            return quote(new String(c.get(row), StandardCharsets.UTF_8));
        }
        throw new IllegalArgumentException("no CSV rendering for " + v.getClass().getSimpleName());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
