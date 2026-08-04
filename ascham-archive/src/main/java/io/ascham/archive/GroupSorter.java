package io.ascham.archive;

import io.ascham.schema.ArenaSchema;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.FieldVector;
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
import org.apache.arrow.vector.types.pojo.ArrowType;

/**
 * The native index sort: orders a {@link SegmentGroup}'s rows by the configured sort columns
 * without materialising the rows. Only the key columns are pulled onto the heap, each normalised
 * into a {@code long[]} whose natural order equals the column's order — signed integers and
 * timestamps as themselves, unsigned 64-bit values sign-flipped, floats via the IEEE-754
 * total-order transform, and varlen columns as ranks over their sorted distinct values. The
 * permutation is then merge-sorted (stable, so equal keys keep segment append order and the result
 * is deterministic) and rows stream out of the mmap'd roots in permuted order.
 *
 * <p>Memory is bounded by the key columns of one file group: 8 bytes per row per sort column, plus
 * the distinct values of any varlen key.
 *
 * <p>Collation for varlen keys is unsigned byte order — identical to UTF-8 code-point order and to
 * the binary collation the DuckDB roller used. Nulls sort first (Iceberg's default null order).
 */
final class GroupSorter {

    private GroupSorter() {
    }

    /** The row order to write: a permutation of {@code [0, group.rowCount())}. */
    static int[] sortedIndex(SegmentGroup group, List<String> sortColumns) {
        int n = group.rowCount();
        int[] index = new int[n];
        for (int i = 0; i < n; i++) {
            index[i] = i;
        }
        if (n == 0 || sortColumns.isEmpty()) {
            return index;
        }

        long[][] keys = new long[sortColumns.size()][];
        for (int c = 0; c < sortColumns.size(); c++) {
            keys[c] = extractKeys(group, ordinalOf(group.schema(), sortColumns.get(c)));
        }
        mergeSort(index, new int[n], 0, n, keys);
        return index;
    }

    private static int ordinalOf(ArenaSchema schema, String column) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.field(i).getName().equals(column)) {
                return i;
            }
        }
        throw new ArchiveException("sort column '" + column + "' does not exist in the arena schema");
    }

    // --- key extraction ---

    private static long[] extractKeys(SegmentGroup group, int ordinal) {
        ArrowType type = group.schema().field(ordinal).getType();
        return switch (type.getTypeID()) {
            case Utf8, Binary -> rankKeys(group, ordinal);
            default -> longKeys(group, ordinal, type);
        };
    }

    /** Fixed-width keys, normalised so {@code Long.compare} orders them like the column. */
    private static long[] longKeys(SegmentGroup group, int ordinal, ArrowType type) {
        long[] keys = new long[group.rowCount()];
        for (int b = 0; b < group.batchCount(); b++) {
            VectorSchemaRoot root = group.root(b);
            FieldVector vector = root.getVector(ordinal);
            int start = group.batchStart(b);
            for (int row = 0; row < root.getRowCount(); row++) {
                keys[start + row] = vector.isNull(row) ? Long.MIN_VALUE : longValue(vector, row, type);
            }
        }
        return keys;
    }

    private static long longValue(FieldVector vector, int row, ArrowType type) {
        return switch (type.getTypeID()) {
            case Timestamp -> ((TimeStampVector) vector).get(row);
            case Int -> intValue(vector, row, (ArrowType.Int) type);
            case Date -> ((DateDayVector) vector).get(row);
            case Time -> ((TimeNanoVector) vector).get(row);
            case Bool -> ((BitVector) vector).get(row);
            case FloatingPoint -> vector instanceof Float4Vector f
                    ? sortableBits(Float.floatToRawIntBits(f.get(row)))
                    : sortableBits(Double.doubleToRawLongBits(((Float8Vector) vector).get(row)));
            default -> throw new ArchiveException(
                    "sort column type has no index-sort key mapping: " + type);
        };
    }

    private static long intValue(FieldVector vector, int row, ArrowType.Int type) {
        if (type.getIsSigned()) {
            return switch (type.getBitWidth()) {
                case 8 -> ((TinyIntVector) vector).get(row);
                case 16 -> ((SmallIntVector) vector).get(row);
                case 32 -> ((IntVector) vector).get(row);
                default -> ((BigIntVector) vector).get(row);
            };
        }
        return switch (type.getBitWidth()) {
            case 8 -> Byte.toUnsignedInt(((UInt1Vector) vector).get(row));
            case 16 -> ((UInt2Vector) vector).get(row);
            case 32 -> Integer.toUnsignedLong(((UInt4Vector) vector).get(row));
            // u64: flip the sign bit so the signed comparison of the keys is an unsigned
            // comparison of the values.
            default -> ((UInt8Vector) vector).get(row) ^ Long.MIN_VALUE;
        };
    }

    /** IEEE-754 total-order: flips negative values' magnitude bits so bit order = value order. */
    private static long sortableBits(long doubleBits) {
        return doubleBits ^ ((doubleBits >> 63) | Long.MIN_VALUE);
    }

    private static long sortableBits(int floatBits) {
        return floatBits ^ ((floatBits >> 31) | Integer.MIN_VALUE);
    }

    /**
     * Varlen keys as ranks: distinct values are collected, sorted by unsigned byte order, and each
     * row keyed by its value's rank. Heap cost is one int-sized id per row plus the distinct values
     * — for symbol-like columns, effectively nothing.
     */
    private static long[] rankKeys(SegmentGroup group, int ordinal) {
        int n = group.rowCount();
        int[] ids = new int[n];
        Map<ByteKey, Integer> distinct = new HashMap<>();
        for (int b = 0; b < group.batchCount(); b++) {
            VectorSchemaRoot root = group.root(b);
            FieldVector vector = root.getVector(ordinal);
            int start = group.batchStart(b);
            for (int row = 0; row < root.getRowCount(); row++) {
                if (vector.isNull(row)) {
                    ids[start + row] = -1;
                    continue;
                }
                byte[] value = vector instanceof VarCharVector vc
                        ? vc.get(row)
                        : ((VarBinaryVector) vector).get(row);
                ids[start + row] = distinct.computeIfAbsent(new ByteKey(value), k -> distinct.size());
            }
        }

        ByteKey[] values = new ByteKey[distinct.size()];
        distinct.forEach((key, id) -> values[id] = key);
        Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, c) -> Arrays.compareUnsigned(values[a].bytes, values[c].bytes));
        long[] rankOf = new long[values.length];
        for (int rank = 0; rank < order.length; rank++) {
            rankOf[order[rank]] = rank;
        }

        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = ids[i] < 0 ? Long.MIN_VALUE : rankOf[ids[i]];
        }
        return keys;
    }

    private record ByteKey(byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ByteKey other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    // --- permutation sort ---

    /** Stable top-down merge sort of {@code index[lo, hi)} by the key arrays, in key order. */
    private static void mergeSort(int[] index, int[] tmp, int lo, int hi, long[][] keys) {
        if (hi - lo <= 1) {
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(index, tmp, lo, mid, keys);
        mergeSort(index, tmp, mid, hi, keys);
        if (compare(keys, index[mid - 1], index[mid]) <= 0) {
            return; // already ordered across the split
        }
        System.arraycopy(index, lo, tmp, lo, hi - lo);
        int left = lo;
        int right = mid;
        for (int out = lo; out < hi; out++) {
            if (left < mid && (right >= hi || compare(keys, tmp[left], tmp[right]) <= 0)) {
                index[out] = tmp[left++];
            } else {
                index[out] = tmp[right++];
            }
        }
    }

    private static int compare(long[][] keys, int a, int b) {
        for (long[] key : keys) {
            int c = Long.compare(key[a], key[b]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
