package io.ito.arena.conformance;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.MetadataKeys;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * The golden-corpus case list: schemas × deterministic data with expected segment bytes checked in.
 * This is the cross-language contract — the future C++ reader is validated against exactly these
 * files. Cases cover every supported type plus the spec's enumerated edge cases: empty batch,
 * all-null column, all-non-null column, varlen at exact byte capacity, varlen empty strings,
 * in-progress batch mid-append, FixedSizeBinary at several widths, and min/max at type bounds.
 */
final class GoldenCases {

    private static final long BASE_TS = 1_700_000_000_000_000_000L;

    private GoldenCases() {
    }

    static List<GoldenCase> all() {
        List<GoldenCase> cases = new ArrayList<>();

        // Every supported type, all-non-null, spanning a seal boundary (batch 0 sealed, batch 1 open).
        cases.add(new GoldenCase("all_types", allTypes(4), 2, 1, 1, 5000, 100, 6, w -> {
            GenericAppender a = w.genericAppender();
            for (int r = 0; r < 6; r++) {
                allTypesRow(a, r);
            }
        }));

        // All-null non-required columns (only the required time + stats columns are set).
        cases.add(new GoldenCase("all_null", allTypes(8), 1, 1, 1, 5000, 100, 3, w -> {
            GenericAppender a = w.genericAppender();
            for (int r = 0; r < 3; r++) {
                a.beginRow();
                a.setLong(0, BASE_TS + r);
                a.setLong(5, r);
                a.endRow();
            }
        }));

        // Empty sealed batch (seal with zero rows).
        cases.add(new GoldenCase("empty_batch", allTypes(8), 2, 1, 1, 5000, 100, 0, SegmentWriter::seal));

        // In-progress batch mid-append (never sealed).
        cases.add(new GoldenCase("in_progress", allTypes(8), 1, 1, 1, 5000, 100, 5, w -> {
            GenericAppender a = w.genericAppender();
            for (int r = 0; r < 5; r++) {
                allTypesRow(a, r);
            }
        }));

        // Varlen exactly filling capacity, then a byte that forces a capacity seal + open-row migration.
        cases.add(new GoldenCase("varlen_exact_capacity", varlen(100, 16), 2, 1, 1, 5000, 100, 2, w -> {
            GenericAppender a = w.genericAppender();
            a.beginRow();
            a.setLong(0, BASE_TS);
            a.setLong(1, 10);
            a.setBytes(2, filled(16, (byte) 'A'), 0, 16);
            a.endRow();
            a.beginRow();
            a.setLong(0, BASE_TS + 1);
            a.setLong(1, 20);
            a.setBytes(2, filled(1, (byte) 'B'), 0, 1);
            a.endRow();
        }));

        // Varlen empty strings (offsets[n+1] == offsets[n]).
        cases.add(new GoldenCase("varlen_empty", varlen(8, 64), 2, 1, 1, 5000, 100, 3, w -> {
            GenericAppender a = w.genericAppender();
            for (int r = 0; r < 3; r++) {
                a.beginRow();
                a.setLong(0, BASE_TS + r);
                a.setLong(1, r);
                a.setBytes(2, filled(0, (byte) 0), 0, 0);
                a.endRow();
            }
            w.seal();
        }));

        // FixedSizeBinary at several widths {1, 7, 16, 33}.
        cases.add(new GoldenCase("fixed_binary_widths", fixedBinary(8), 2, 1, 1, 5000, 100, 4, w -> {
            GenericAppender a = w.genericAppender();
            for (int r = 0; r < 4; r++) {
                a.beginRow();
                a.setLong(0, BASE_TS + r);
                a.setLong(1, r);
                a.setFixedBytes(2, pattern(1, r), 0, 1);
                a.setFixedBytes(3, pattern(7, r), 0, 7);
                a.setFixedBytes(4, pattern(16, r), 0, 16);
                a.setFixedBytes(5, pattern(33, r), 0, 33);
                a.endRow();
            }
            w.seal();
        }));

        // Min/max at type bounds (integer extremes, unsigned maxima, Decimal128 ±(2^127-1)).
        cases.add(new GoldenCase("type_bounds", bounds(8), 2, 1, 1, 5000, 100, 3, w -> {
            GenericAppender a = w.genericAppender();
            boundsRow(a, 0, Byte.MIN_VALUE, Short.MIN_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE,
                    (byte) 0, (short) 0, 0, 0L, -Float.MAX_VALUE, -Double.MAX_VALUE, 0L, Long.MIN_VALUE);
            boundsRow(a, 1, Byte.MAX_VALUE, Short.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE,
                    (byte) 0xFF, (short) 0xFFFF, -1, -1L, Float.MAX_VALUE, Double.MAX_VALUE, -1L, Long.MAX_VALUE);
            boundsRow(a, 2, (byte) 0, (short) 0, 0, 0L,
                    (byte) 0, (short) 0, 0, 0L, 0f, 0d, 0L, 0L);
            w.seal();
        }));

        return cases;
    }

    // --- Row scripts ---

    private static void allTypesRow(GenericAppender a, int r) {
        a.beginRow();
        a.setLong(0, BASE_TS + r);
        a.setBool(1, (r & 1) == 0);
        a.setByte(2, (byte) r);
        a.setShort(3, (short) (r * 7));
        a.setInt(4, r * 1000);
        a.setLong(5, r * 1_000_000L);
        a.setFloat(6, r + 0.5f);
        a.setDouble(7, r + 0.25);
        a.setDecimal128(8, r * 123_456_789L, 0);
        a.setInt(9, 19_000 + r);
        a.setLong(10, r * 1000L);
        a.setFixedBytes(11, pattern(16, r), 0, 16);
        byte[] sym = ("s" + r).getBytes(StandardCharsets.UTF_8);
        a.setBytes(12, new UnsafeBuffer(sym), 0, sym.length);
        a.setBytes(13, new UnsafeBuffer(new byte[]{(byte) r, (byte) (r + 1)}), 0, 2);
        a.endRow();
    }

    private static void boundsRow(GenericAppender a, int r, byte i8, short i16, int i32, long i64,
                                  byte u8, short u16, int u32, long u64, float f32, double f64,
                                  long decLow, long decHigh) {
        a.beginRow();
        a.setLong(0, BASE_TS + r);
        a.setByte(1, i8);
        a.setShort(2, i16);
        a.setInt(3, i32);
        a.setLong(4, i64);
        a.setByte(5, u8);
        a.setShort(6, u16);
        a.setInt(7, u32);
        a.setLong(8, u64);
        a.setFloat(9, f32);
        a.setDouble(10, f64);
        a.setDecimal128(11, decLow, decHigh);
        a.endRow();
    }

    // --- Schemas ---

    static ArenaSchema allTypes(int batchRows) {
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
                field("sym", new ArrowType.Utf8(), varlenBytes(64)),
                field("bin", new ArrowType.Binary(), varlenBytes(64)));
        return load(fields, "all_types", "ts", "i64", batchRows);
    }

    static ArenaSchema varlen(int batchRows, int capacity) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)),
                field("sym", new ArrowType.Utf8(), varlenBytes(capacity)));
        return load(fields, "varlen", "ts", "i64", batchRows);
    }

    static ArenaSchema fixedBinary(int batchRows) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)),
                field("b1", new ArrowType.FixedSizeBinary(1)),
                field("b7", new ArrowType.FixedSizeBinary(7)),
                field("b16", new ArrowType.FixedSizeBinary(16)),
                field("b33", new ArrowType.FixedSizeBinary(33)));
        return load(fields, "fixed_binary", "ts", "i64", batchRows);
    }

    static ArenaSchema bounds(int batchRows) {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i8", new ArrowType.Int(8, true)),
                field("i16", new ArrowType.Int(16, true)),
                field("i32", new ArrowType.Int(32, true)),
                field("i64", new ArrowType.Int(64, true)),
                field("u8", new ArrowType.Int(8, false)),
                field("u16", new ArrowType.Int(16, false)),
                field("u32", new ArrowType.Int(32, false)),
                field("u64", new ArrowType.Int(64, false)),
                field("f32", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                field("f64", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                field("dec", new ArrowType.Decimal(38, 9, 128)));
        return load(fields, "bounds", "ts", "i64", batchRows);
    }

    // --- Helpers ---

    private static ArenaSchema load(List<Field> fields, String table, String timeCol, String statsCol, int batchRows) {
        Map<String, String> meta = new TreeMap<>();
        meta.put(MetadataKeys.TABLE, table);
        meta.put(MetadataKeys.SCHEMA_VERSION, "1");
        meta.put(MetadataKeys.TIME_COLUMN, timeCol);
        meta.put(MetadataKeys.STATS_COLUMN, statsCol);
        meta.put(MetadataKeys.BATCH_ROWS, Integer.toString(batchRows));
        return ArenaSchema.load(new Schema(fields, meta));
    }

    private static Field field(String name, ArrowType type) {
        return field(name, type, Map.of());
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }

    private static Map<String, String> varlenBytes(int n) {
        return Map.of(MetadataKeys.VARLEN_BYTES, Integer.toString(n));
    }

    private static UnsafeBuffer filled(int n, byte value) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, value);
        return new UnsafeBuffer(b);
    }

    private static UnsafeBuffer pattern(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (seed * n + i);
        }
        return new UnsafeBuffer(b);
    }
}
