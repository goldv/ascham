package io.ito.arena.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchViewArrowValuesTest {

    @TempDir
    Path dir;

    private static final int TS = 0, FLAG = 1, I32 = 4, I64 = 5, F64 = 7, DEC = 8, SYM = 12, BIN = 13;

    @Test
    void everyKindReadsThroughZeroCopyVectors() {
        ArenaSchema schema = ReaderFixtures.allTypesSchema(64);
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 4, 1L, 1L, new ReaderFixtures.FakeClock(0, 1))) {
            GenericAppender a = writer.genericAppender();
            // Row 0: fully populated.
            row(a, 1000, true, 111, 1_000_000L, 1.5, "AAA", new byte[]{1, 2});
            // Row 1: i32 and sym null.
            a.beginRow();
            a.setLong(TS, 1001);
            a.setBool(FLAG, false);
            a.setLong(I64, 2_000_000L);
            a.setDouble(F64, 2.5);
            a.setDecimal128(DEC, 123_456_789L, 0);
            a.setBytes(BIN, buf(new byte[]{3}), 0, 1);
            a.endRow();
            // Row 2: sym empty, bin empty.
            row(a, 1002, true, 333, 3_000_000L, 3.5, "CCC", new byte[0]);
            writer.seal();
        }

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            Snapshot snapshot = reader.snapshot();
            // seal() sealed batch 0 and opened an empty in-progress batch 1.
            assertThat(snapshot.batchCount()).isEqualTo(2);
            assertThat(snapshot.batches().get(1).rowCount()).isZero();
            assertThat(snapshot.batches().get(1).sealed()).isFalse();
            BatchView view = snapshot.batches().get(0);
            assertThat(view.rowCount()).isEqualTo(3);
            assertThat(view.sealed()).isTrue();

            try (VectorSchemaRoot root = view.root()) {
                assertThat(root.getRowCount()).isEqualTo(3);

                TimeStampNanoTZVector ts = (TimeStampNanoTZVector) root.getVector("ts");
                assertThat(ts.get(0)).isEqualTo(1000);
                assertThat(ts.get(2)).isEqualTo(1002);

                BitVector flag = (BitVector) root.getVector("flag");
                assertThat(flag.get(0)).isEqualTo(1);
                assertThat(flag.get(1)).isEqualTo(0);

                IntVector i32 = (IntVector) root.getVector("i32");
                assertThat(i32.isNull(1)).isTrue();
                assertThat(i32.get(0)).isEqualTo(111);
                assertThat(i32.get(2)).isEqualTo(333);

                BigIntVector i64 = (BigIntVector) root.getVector("i64");
                assertThat(i64.get(0)).isEqualTo(1_000_000L);

                Float8Vector f64 = (Float8Vector) root.getVector("f64");
                assertThat(f64.get(0)).isEqualTo(1.5);

                DecimalVector dec = (DecimalVector) root.getVector("dec");
                assertThat(dec.getObject(0)).isEqualByComparingTo(new BigDecimal("0.123456789"));

                VarCharVector sym = (VarCharVector) root.getVector("sym");
                assertThat(sym.isNull(1)).isTrue();
                assertThat(new String(sym.get(0), StandardCharsets.UTF_8)).isEqualTo("AAA");
                assertThat(new String(sym.get(2), StandardCharsets.UTF_8)).isEqualTo("CCC");

                VarBinaryVector bin = (VarBinaryVector) root.getVector("bin");
                assertThat(bin.get(0)).containsExactly(1, 2);
                assertThat(bin.get(2)).isEmpty();
            }
        }
    }

    private static void row(GenericAppender a, long ts, boolean flag, int i32, long i64,
                            double f64, String sym, byte[] bin) {
        a.beginRow();
        a.setLong(TS, ts);
        a.setBool(FLAG, flag);
        a.setInt(I32, i32);
        a.setLong(I64, i64);
        a.setDouble(F64, f64);
        a.setDecimal128(DEC, 123_456_789L, 0);
        a.setBytes(SYM, buf(sym.getBytes(StandardCharsets.UTF_8)), 0, sym.length());
        a.setBytes(BIN, buf(bin), 0, bin.length);
        a.endRow();
    }

    private static UnsafeBuffer buf(byte[] bytes) {
        return new UnsafeBuffer(bytes);
    }
}
