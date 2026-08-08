package io.ascham.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ascham.rotate.RollCycle;
import io.ascham.rotate.RotatingWriter;
import io.ascham.rotate.SegmentDirectory;
import io.ascham.schema.ArenaSchema;
import io.ascham.schema.MetadataKeys;
import io.ascham.write.Appender;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The index sort over zero-copy segment roots: SegmentGroup addressing + GroupSorter ordering. */
class GroupSorterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 30);

    @TempDir
    Path base;

    @Test
    void groupSpansSegmentsAndResolvesGlobalRows() {
        List<Path> segments = writeShuffledDay(300, true);
        assertThat(segments).hasSizeGreaterThan(1); // the forced rotation split the day

        try (SegmentGroup group = SegmentGroup.open(segments)) {
            assertThat(group.rowCount()).isEqualTo(300);
            // Every global row resolves to the batch whose window contains it.
            for (int g = 0; g < group.rowCount(); g++) {
                int b = group.batchOf(g);
                assertThat(g).isGreaterThanOrEqualTo(group.batchStart(b));
                assertThat(g - group.batchStart(b)).isLessThan(group.root(b).getRowCount());
            }
        }
    }

    @Test
    void sortsBySymbolThenTimeAcrossSegmentAndBatchBoundaries() {
        List<Path> segments = writeShuffledDay(300, true);
        try (SegmentGroup group = SegmentGroup.open(segments)) {
            int[] index = GroupSorter.sortedIndex(group, List.of("sym", "ts"));

            assertThat(index).hasSize(300);
            assertThat(java.util.Arrays.stream(index).sorted().toArray())
                    .isEqualTo(java.util.stream.IntStream.range(0, 300).toArray()); // a permutation

            String prevSym = null;
            long prevTs = Long.MIN_VALUE;
            for (int g : index) {
                int b = group.batchOf(g);
                int row = g - group.batchStart(b);
                String sym = new String(((VarCharVector) group.root(b).getVector(1)).get(row),
                        StandardCharsets.UTF_8);
                long ts = ((TimeStampNanoTZVector) group.root(b).getVector(0)).get(row);
                if (prevSym != null) {
                    assertThat(sym).isGreaterThanOrEqualTo(prevSym);
                    if (sym.equals(prevSym)) {
                        assertThat(ts).isGreaterThanOrEqualTo(prevTs);
                    }
                }
                prevSym = sym;
                prevTs = ts;
            }
        }
    }

    @Test
    void timeOnlySortRecoversAppendOrderOfAShuffledDay() {
        List<Path> segments = writeShuffledDay(200, false);
        try (SegmentGroup group = SegmentGroup.open(segments)) {
            int[] index = GroupSorter.sortedIndex(group, List.of("ts"));
            long prev = Long.MIN_VALUE;
            for (int g : index) {
                int b = group.batchOf(g);
                long ts = ((TimeStampNanoTZVector) group.root(b).getVector(0))
                        .get(g - group.batchStart(b));
                assertThat(ts).isGreaterThanOrEqualTo(prev);
                prev = ts;
            }
        }
    }

    @Test
    void unknownSortColumnIsRejected() {
        List<Path> segments = writeShuffledDay(10, false);
        try (SegmentGroup group = SegmentGroup.open(segments)) {
            assertThatThrownBy(() -> GroupSorter.sortedIndex(group, List.of("nope")))
                    .isInstanceOf(ArchiveException.class)
                    .hasMessageContaining("nope");
        }
    }

    @Test
    void doubleKeysSortInNumericOrder() {
        // Spans the sign boundary, both zeros, both infinities and NaN — the whole IEEE-754 total
        // order, which is what the key transform has to reproduce under a signed comparison.
        double[] values = {3.5, -1.0, 0.0, Double.NaN, -0.0, 1e308, -1e308, 2.5, -7.25,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        List<Path> segments = writeKeyed("dbl",
                field("k", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
                values.length, (a, r) -> a.setDouble(1, values[r]));

        try (SegmentGroup group = SegmentGroup.open(segments)) {
            double[] got = new double[values.length];
            int i = 0;
            for (int g : GroupSorter.sortedIndex(group, List.of("k"))) {
                int b = group.batchOf(g);
                got[i++] = ((Float8Vector) group.root(b).getVector(1)).get(g - group.batchStart(b));
            }
            double[] want = values.clone();
            Arrays.sort(want); // -0.0 before 0.0, NaN last: exactly the order the keys must give
            assertThat(got).isEqualTo(want);
        }
    }

    @Test
    void floatKeysSortInNumericOrder() {
        float[] values = {3.5f, -1.0f, 0.0f, Float.NaN, -0.0f, 3.4e38f, -3.4e38f, 2.5f, -7.25f,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY};
        List<Path> segments = writeKeyed("flt",
                field("k", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                values.length, (a, r) -> a.setFloat(1, values[r]));

        try (SegmentGroup group = SegmentGroup.open(segments)) {
            float[] got = new float[values.length];
            int i = 0;
            for (int g : GroupSorter.sortedIndex(group, List.of("k"))) {
                int b = group.batchOf(g);
                got[i++] = ((Float4Vector) group.root(b).getVector(1)).get(g - group.batchStart(b));
            }
            float[] want = values.clone();
            Arrays.sort(want);
            assertThat(got).isEqualTo(want);
        }
    }

    @Test
    void nullsSortBeforeUnsignedZeroAndValuesStayUnsigned() {
        // u64 keys are sign-flipped, so value 0 keys to Long.MIN_VALUE — the same key the null
        // sentinel uses. Nulls must still come first, and -1L must read as the unsigned maximum.
        Long[] values = {0L, null, -1L, 1L, null, 0L, Long.MIN_VALUE};
        List<Path> segments = writeKeyed("u64", field("k", new ArrowType.Int(64, false)),
                values.length, (a, r) -> {
                    if (values[r] == null) {
                        a.setNull(1);
                    } else {
                        a.setLong(1, values[r]);
                    }
                });

        try (SegmentGroup group = SegmentGroup.open(segments)) {
            List<Long> got = new ArrayList<>();
            for (int g : GroupSorter.sortedIndex(group, List.of("k"))) {
                int b = group.batchOf(g);
                int row = g - group.batchStart(b);
                UInt8Vector v = (UInt8Vector) group.root(b).getVector(1);
                got.add(v.isNull(row) ? null : v.get(row));
            }
            // nulls, then 0, 0, 1, then unsigned 2^63 and 2^64-1.
            assertThat(got).containsExactly(null, null, 0L, 0L, 1L, Long.MIN_VALUE, -1L);
        }
    }

    @Test
    void decimalKeysSortNumericallyBeyondLongRange() {
        // Values that need all 128 bits: ±2^70 and 2^100 differ only in the high word, and
        // -1/0/1 only in the low — so both halves of the key pair are exercised.
        BigInteger[] unscaled = {
                BigInteger.ONE.shiftLeft(100), BigInteger.valueOf(-5), null, BigInteger.ZERO,
                BigInteger.ONE.shiftLeft(70).negate(), BigInteger.ONE, BigInteger.ONE.shiftLeft(70),
                BigInteger.valueOf(-1), BigInteger.valueOf(Long.MIN_VALUE)};
        List<Path> segments = writeKeyed("dec", field("k", new ArrowType.Decimal(38, 4, 128)),
                unscaled.length, (a, r) -> {
                    if (unscaled[r] == null) {
                        a.setNull(1);
                    } else {
                        a.setDecimal128(1, unscaled[r].longValue(),
                                unscaled[r].shiftRight(64).longValue());
                    }
                });

        try (SegmentGroup group = SegmentGroup.open(segments)) {
            List<BigDecimal> got = new ArrayList<>();
            for (int g : GroupSorter.sortedIndex(group, List.of("k"))) {
                int b = group.batchOf(g);
                int row = g - group.batchStart(b);
                DecimalVector v = (DecimalVector) group.root(b).getVector(1);
                got.add(v.isNull(row) ? null : v.getObject(row));
            }

            assertThat(got.get(0)).isNull(); // the sole null sorts first
            List<BigDecimal> values = got.subList(1, got.size());
            assertThat(values).hasSize(unscaled.length - 1);
            for (int i = 1; i < values.size(); i++) {
                assertThat(values.get(i)).isGreaterThan(values.get(i - 1));
            }
            assertThat(values.get(0).unscaledValue()).isEqualTo(BigInteger.ONE.shiftLeft(70).negate());
            assertThat(values.get(values.size() - 1).unscaledValue())
                    .isEqualTo(BigInteger.ONE.shiftLeft(100));
        }
    }

    @Test
    void fixedSizeBinaryKeysSortInUnsignedByteOrder() {
        // 0xFF must land last: byte-signed comparison would put it first.
        byte[][] values = {
                {0x00, 0x00, 0x00, 0x01},
                {(byte) 0xFF, 0x00, 0x00, 0x00},
                {0x00, 0x00, 0x00, 0x00},
                {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                {(byte) 0x80, 0x00, 0x00, 0x00}};
        List<Path> segments = writeKeyed("fsb", field("k", new ArrowType.FixedSizeBinary(4)),
                values.length, (a, r) -> a.setFixedBytes(1, new UnsafeBuffer(values[r]), 0, 4));

        try (SegmentGroup group = SegmentGroup.open(segments)) {
            List<byte[]> got = new ArrayList<>();
            for (int g : GroupSorter.sortedIndex(group, List.of("k"))) {
                int b = group.batchOf(g);
                got.add(((FixedSizeBinaryVector) group.root(b).getVector(1))
                        .get(g - group.batchStart(b)));
            }
            byte[][] want = values.clone();
            Arrays.sort(want, Arrays::compareUnsigned);
            assertThat(got).containsExactly(want);
        }
    }

    @Test
    void equalKeysKeepAppendOrder() {
        List<Path> segments = writeKeyed("flat", field("k", new ArrowType.Int(64, true)),
                50, (a, r) -> a.setLong(1, 7L));
        try (SegmentGroup group = SegmentGroup.open(segments)) {
            // Stability is what makes the roll deterministic across runs and replay — no tie-break
            // comparator needed.
            assertThat(GroupSorter.sortedIndex(group, List.of("k")))
                    .isEqualTo(java.util.stream.IntStream.range(0, 50).toArray());
        }
    }

    @Test
    void unsealedBatchesAreAHardError() {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ArchiveFixtures.MutableClock clock =
                new ArchiveFixtures.MutableClock(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, ArchiveFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, ArchiveFixtures.counterNanoClock())) {
            ArchiveFixtures.append(writer, dayNanos(0), "AAPL", 1L);
            List<Path> segments = dir.list().stream().map(SegmentDirectory.SegmentName::path).toList();
            // The writer is still open: the row's batch is unsealed, so the group must refuse.
            assertThatThrownBy(() -> SegmentGroup.open(segments))
                    .isInstanceOf(ArchiveException.class)
                    .hasMessageContaining("not sealed");
        }
    }

    /**
     * One UTC day of rows with symbols round-robined (so append order is NOT symbol order), split
     * across two segments via a forced mid-day rotation when {@code split}. Timestamps ascend with
     * a sub-microsecond jitter component.
     */
    private List<Path> writeShuffledDay(int rows, boolean split) {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ArchiveFixtures.MutableClock clock =
                new ArchiveFixtures.MutableClock(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, ArchiveFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, ArchiveFixtures.counterNanoClock())) {
            for (int r = 0; r < rows; r++) {
                if (split && r == rows / 2) {
                    writer.rotate(); // capacity-style rotation: same day, next sequence
                }
                ArchiveFixtures.append(writer, dayNanos(r),
                        ArchiveFixtures.SYMBOLS[r % ArchiveFixtures.SYMBOLS.length], 1_000L + r);
            }
        }
        // Move the clock past the day so nothing considers these segments live.
        clock.set(DAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        List<Path> segments = new ArrayList<>();
        dir.list().forEach(s -> segments.add(s.path()));
        return segments;
    }

    /** Fills the key column of row {@code r}; column 0 is the time column the harness writes. */
    private interface KeySetter {
        void set(Appender appender, int row);
    }

    /**
     * One table of {@code (ts, k)} rows with {@code k} typed by the caller, written through the real
     * writer and sealed on close. The batch size is small so the rows straddle batch boundaries.
     */
    private List<Path> writeKeyed(String table, Field key, int rows, KeySetter setter) {
        ArenaSchema schema = ArenaSchema.load(new Schema(
                List.of(field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")), key),
                Map.of(MetadataKeys.TABLE, table,
                        MetadataKeys.SCHEMA_VERSION, "1",
                        MetadataKeys.TIME_COLUMN, "ts",
                        MetadataKeys.BATCH_ROWS, "4")));
        SegmentDirectory dir = new SegmentDirectory(base, table);
        ArchiveFixtures.MutableClock clock =
                new ArchiveFixtures.MutableClock(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 4096, 1L,
                RollCycle.DAILY, clock, ArchiveFixtures.counterNanoClock())) {
            for (int r = 0; r < rows; r++) {
                Appender a = writer.appender();
                a.beginRow();
                a.setLong(0, dayNanos(r));
                setter.set(a, r);
                a.endRow();
            }
        }
        List<Path> segments = new ArrayList<>();
        dir.list().forEach(s -> segments.add(s.path()));
        return segments;
    }

    private static Field field(String name, ArrowType type) {
        return new Field(name, new FieldType(true, type, null, Map.of()), List.of());
    }

    private static long dayNanos(int row) {
        Instant start = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        return start.getEpochSecond() * 1_000_000_000L + row * 1_000_000L + (row % 997);
    }
}
