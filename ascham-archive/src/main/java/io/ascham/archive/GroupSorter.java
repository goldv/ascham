package io.ascham.archive;

import io.ascham.schema.ArenaSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.agrona.collections.Object2IntHashMap;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;

/**
 * The native index sort: orders a {@link SegmentGroup}'s rows by the configured sort columns
 * without materialising the rows. Only the key columns are pulled onto the heap, each normalised
 * into {@code long[]}s whose natural order equals the column's order — signed integers and
 * timestamps as themselves, unsigned 64-bit values sign-flipped, floats via the IEEE-754
 * total-order transform, decimals as a (high, low) word pair, and binary-ish columns as ranks over
 * their sorted distinct values. The permutation is then merge-sorted (stable, so equal keys keep
 * segment append order and the result is deterministic) and rows stream out of the mmap'd roots in
 * permuted order.
 *
 * <p>Memory is bounded by the key columns of one file group: 8 bytes per row per sort column, plus
 * the distinct values of any binary key.
 *
 * <p>Collation for binary keys is unsigned byte order — identical to UTF-8 code-point order and to
 * the binary collation the DuckDB roller used. Nulls sort first (Iceberg's default null order),
 * keyed as {@link Long#MIN_VALUE}; where a real value can key to that too, an extra leading flag
 * key keeps them distinct (see {@link #withNullFlags}).
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

        // A column can contribute more than one key array — a leading null flag, or a decimal's two
        // words — so the per-column results are flattened into one lexicographic list.
        List<long[]> keys = new ArrayList<>(sortColumns.size());
        for (String column : sortColumns) {
            Collections.addAll(keys, extractKeys(group, ordinalOf(group.schema(), column)));
        }
        mergeSort(index, new int[n], 0, n, keys.toArray(new long[0][]));
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

    private static long[][] extractKeys(SegmentGroup group, int ordinal) {
        ArrowType type = group.schema().field(ordinal).getType();
        return switch (type.getTypeID()) {
            case Utf8, Binary, FixedSizeBinary -> new long[][] {rankKeys(group, ordinal)};
            case Decimal -> decimalKeys(group, ordinal);
            default -> longKeys(group, ordinal, type);
        };
    }

    /** Fixed-width keys, normalised so {@code Long.compare} orders them like the column. */
    private static long[][] longKeys(SegmentGroup group, int ordinal, ArrowType type) {
        long[] keys = new long[group.rowCount()];
        boolean sawNull = false;
        for (int b = 0; b < group.batchCount(); b++) {
            VectorSchemaRoot root = group.root(b);
            FieldVector vector = root.getVector(ordinal);
            int start = group.batchStart(b);
            for (int row = 0; row < root.getRowCount(); row++) {
                boolean isNull = vector.isNull(row);
                sawNull |= isNull;
                keys[start + row] = isNull ? Long.MIN_VALUE : longValue(vector, row, type);
            }
        }
        return withNullFlags(group, ordinal, sawNull && reachesSentinel(type), keys);
    }

    /**
     * Decimal128 as two keys: the high word compared signed, the low word compared unsigned. Scale
     * is fixed per column, so lexicographic (high, low) order over the two's-complement value is its
     * numeric order. The words are read straight off the buffer — Arrow lays decimals out
     * little-endian, low word first, exactly as {@code Appender.setDecimal128} writes them — rather
     * than materialising a {@code BigDecimal} per row.
     */
    private static long[][] decimalKeys(SegmentGroup group, int ordinal) {
        long[] high = new long[group.rowCount()];
        long[] low = new long[group.rowCount()];
        boolean sawNull = false;
        for (int b = 0; b < group.batchCount(); b++) {
            VectorSchemaRoot root = group.root(b);
            DecimalVector vector = (DecimalVector) root.getVector(ordinal);
            ArrowBuf data = vector.getDataBuffer();
            int start = group.batchStart(b);
            for (int row = 0; row < root.getRowCount(); row++) {
                if (vector.isNull(row)) {
                    sawNull = true;
                    high[start + row] = Long.MIN_VALUE;
                    low[start + row] = Long.MIN_VALUE; // an unsigned zero low word
                    continue;
                }
                long at = (long) row * DecimalVector.TYPE_WIDTH;
                high[start + row] = data.getLong(at + Long.BYTES);
                low[start + row] = data.getLong(at) ^ Long.MIN_VALUE;
            }
        }
        return withNullFlags(group, ordinal, sawNull, high, low);
    }

    /**
     * Prepends a 0/1 null flag ahead of {@code values} when the column holds nulls its own key space
     * cannot keep distinct: {@link Long#MIN_VALUE} doubles as the null sentinel, and a full-range key
     * can produce it from a real value. Allocated only in that case, so the usual non-null key pays
     * nothing and the memory bound stays one {@code long} per row per sort column.
     */
    private static long[][] withNullFlags(SegmentGroup group, int ordinal, boolean needed, long[]... values) {
        if (!needed) {
            return values;
        }
        long[] flags = new long[group.rowCount()];
        for (int b = 0; b < group.batchCount(); b++) {
            VectorSchemaRoot root = group.root(b);
            FieldVector vector = root.getVector(ordinal);
            int start = group.batchStart(b);
            for (int row = 0; row < root.getRowCount(); row++) {
                flags[start + row] = vector.isNull(row) ? 0 : 1; // nulls first
            }
        }
        long[][] keys = new long[values.length + 1][];
        keys[0] = flags;
        System.arraycopy(values, 0, keys, 1, values.length);
        return keys;
    }

    /**
     * Whether a real value of this type can key to {@link Long#MIN_VALUE} and so tie with a null:
     * int64 at its minimum, uint64 zero (the key is sign-flipped), a raw timestamp at the minimum,
     * and the all-ones double NaN. Narrower types cannot reach it; decimals always can, and are
     * handled in {@link #decimalKeys}.
     */
    private static boolean reachesSentinel(ArrowType type) {
        return switch (type.getTypeID()) {
            case Int -> ((ArrowType.Int) type).getBitWidth() == 64;
            case Timestamp -> true;
            case FloatingPoint -> ((ArrowType.FloatingPoint) type).getPrecision()
                    == FloatingPointPrecision.DOUBLE;
            default -> false;
        };
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

    /**
     * IEEE-754 total order: flips a negative value's magnitude bits so bit order = value order,
     * leaving the sign bit alone. Masking with {@code MAX_VALUE} rather than setting the sign bit is
     * what makes the result comparable by <em>signed</em> {@link Long#compare} — negatives stay
     * negative and reverse among themselves, positives keep their order above them. Setting the sign
     * bit instead yields the unsigned-comparable form, which under a signed compare sorts every
     * positive below every negative.
     */
    private static long sortableBits(long doubleBits) {
        return doubleBits ^ ((doubleBits >> 63) & Long.MAX_VALUE);
    }

    /** As above for float; the int result sign-extends to long without disturbing the order. */
    private static long sortableBits(int floatBits) {
        return floatBits ^ ((floatBits >> 31) & Integer.MAX_VALUE);
    }

    /**
     * Binary keys as ranks: distinct values are collected, sorted by unsigned byte order, and each
     * row keyed by its value's rank. Heap cost is one int-sized id per row plus the distinct values
     * — for symbol-like columns, effectively nothing. The doc-recommended "sort on the int32 code,
     * join the label afterwards" trick, applied generally: one byte-wise compare per distinct value
     * rather than one per comparison probe.
     *
     * <p>Ranks are non-negative, so they never collide with the {@link Long#MIN_VALUE} null
     * sentinel and no flag key is needed.
     */
    private static long[] rankKeys(SegmentGroup group, int ordinal) {
        int n = group.rowCount();
        int[] ids = new int[n];
        Object2IntHashMap<ByteKey> distinct = new Object2IntHashMap<>(-1);

        for (int b = 0; b < group.batchCount(); b++) {
            VectorSchemaRoot root = group.root(b);
            FieldVector vector = root.getVector(ordinal);
            // FixedSizeBinary is not a variable-width vector, so the byte accessor is picked per
            // batch rather than cast once.
            BytesAt at = vector instanceof FixedSizeBinaryVector fixed
                    ? fixed::get
                    : ((BaseVariableWidthVector) vector)::get;
            int start = group.batchStart(b);
            int rows = root.getRowCount();
            for (int row = 0; row < rows; row++) {
                if (vector.isNull(row)) {
                    ids[start + row] = -1;
                    continue;
                }
                ByteKey key = new ByteKey(at.get(row));
                int id = distinct.getValue(key);
                if (id == distinct.missingValue()) {
                    id = distinct.size();
                    distinct.put(key, id);
                }
                ids[start + row] = id;
            }
        }

        ByteKey[] values = distinct.keySet().toArray(new ByteKey[0]);
        Arrays.sort(values, (a, c) -> Arrays.compareUnsigned(a.bytes, c.bytes));

        long[] rankOf = new long[values.length];
        for (int rank = 0; rank < values.length; rank++) {
            rankOf[distinct.get(values[rank])] = rank;
        }

        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = ids[i] < 0 ? Long.MIN_VALUE : rankOf[ids[i]];
        }
        return keys;
    }

    /** A row's bytes, however the vector stores them. */
    @FunctionalInterface
    private interface BytesAt {
        byte[] get(int row);
    }

    private record ByteKey(byte[] bytes) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ByteKey(byte[] bytes1) && Arrays.equals(bytes, bytes1);
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
