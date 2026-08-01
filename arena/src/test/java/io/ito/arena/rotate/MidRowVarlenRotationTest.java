package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.read.BatchView;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.write.Appender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one capacity case {@code beginRow} cannot predict: a varlen column exhausts its per-batch
 * bytes mid-row while the segment is on its last batch. The partially-written open row must be
 * adopted into the successor segment — no exception, no replay, and the pending row stats must
 * travel with it so they seal into the new segment.
 */
class MidRowVarlenRotationTest {

    @TempDir
    Path base;

    private static final int TS = 0, I64 = 1, SYM = 2, BIN = 3;

    @Test
    void varlenExhaustionOnLastBatchAdoptsOpenRowIntoSuccessor() {
        SegmentDirectory dir = new SegmentDirectory(base, "varlen");
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));
        Path second;

        // maxBatches=1: any varlen overflow is in the segment's last batch by construction.
        // batchRows is large so only the byte caps (sym=16, bin=16) can bind.
        try (RotatingWriter writer = RotatingWriter.open(dir, RotateFixtures.varlen(1000, 16, 16), 1, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            Appender a = writer.appender();
            Path first = writer.currentPath();

            // Row 0 fills sym exactly and takes 2 bytes of bin.
            a.beginRow();
            a.setLong(TS, 1000);
            a.setLong(I64, 10);
            a.setBytes(SYM, buf(16, 'A'), 0, 16);
            a.setBytes(BIN, buf(2, 'x'), 0, 2);
            a.endRow();

            // Row 1: fixed cells and 3 bytes of bin land in the old segment; then sym overflows the
            // last batch, so the whole open row — including bin's partial bytes — moves across.
            a.beginRow();
            a.setLong(TS, 2000);
            a.setLong(I64, 20);
            a.setBytes(BIN, buf(3, 'y'), 0, 3);
            a.setBytes(SYM, buf(5, 'B'), 0, 5);
            second = writer.currentPath();
            assertThat(second).isNotEqualTo(first);
            a.endRow();

            // Retired segment: sealed with row 0 only; stats never saw the open row.
            try (SnapshotReader reader = SnapshotReader.open(first)) {
                BatchView b0 = reader.snapshot().batches().get(0);
                assertThat(b0.sealed()).isTrue();
                assertThat(b0.rowCount()).isEqualTo(1);
                assertThat(b0.tsMin()).isEqualTo(1000);
                assertThat(b0.tsMax()).isEqualTo(1000);
                assertThat(b0.statMin()).isEqualTo(10);
                try (VectorSchemaRoot root = b0.root()) {
                    assertThat(str((VarCharVector) root.getVector("sym"), 0)).isEqualTo("A".repeat(16));
                    assertThat(((VarBinaryVector) root.getVector("bin")).get(0))
                            .isEqualTo(bytes(2, 'x'));
                }
            }
            // Successor: the adopted row is batch 0, row 0, every partial value intact.
            try (SnapshotReader reader = SnapshotReader.open(second)) {
                BatchView b0 = reader.snapshot().batches().get(0);
                assertThat(b0.sealed()).isFalse();
                assertThat(b0.rowCount()).isEqualTo(1);
                try (VectorSchemaRoot root = b0.root()) {
                    assertThat(((TimeStampNanoTZVector) root.getVector("ts")).get(0)).isEqualTo(2000);
                    assertThat(((BigIntVector) root.getVector("i64")).get(0)).isEqualTo(20);
                    assertThat(str((VarCharVector) root.getVector("sym"), 0)).isEqualTo("B".repeat(5));
                    assertThat(((VarBinaryVector) root.getVector("bin")).get(0))
                            .isEqualTo(bytes(3, 'y'));
                }
            }
        }

        // Close sealed the successor with the adopted row's stats — proves the pending
        // (unfolded-at-adoption-time) row stats travelled with the row.
        try (SnapshotReader reader = SnapshotReader.open(second)) {
            BatchView b0 = reader.snapshot().batches().get(0);
            assertThat(b0.sealed()).isTrue();
            assertThat(b0.tsMin()).isEqualTo(2000);
            assertThat(b0.tsMax()).isEqualTo(2000);
            assertThat(b0.statMin()).isEqualTo(20);
            assertThat(b0.statMax()).isEqualTo(20);
        }
    }

    @Test
    void rowExceedingColumnCapacityFailsWithoutRotating() {
        SegmentDirectory dir = new SegmentDirectory(base, "varlen");
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, RotateFixtures.varlen(1000, 16, 16), 1, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            Appender a = writer.appender();
            Path first = writer.currentPath();

            // The row's own sym bytes (12 + 5 = 17) overflow an empty batch: no rotation can fit
            // it, so it must fail up front rather than burn a segment.
            a.beginRow();
            a.setLong(TS, 1000);
            a.setBytes(SYM, buf(12, 'A'), 0, 12);
            assertThatThrownBy(() -> a.setBytes(SYM, buf(5, 'B'), 0, 5))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exceed capacity");
            assertThat(writer.currentPath()).isEqualTo(first);
        }
        assertThat(dir.list()).hasSize(1);
    }

    @Test
    void varlenExhaustionAwayFromLastBatchStaysWithinTheSegment() {
        SegmentDirectory dir = new SegmentDirectory(base, "varlen");
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        // maxBatches=2: overflow in batch 0 has a next batch, so the existing intra-segment
        // migration handles it and no rotation may happen (guards the predicate boundary).
        try (RotatingWriter writer = RotatingWriter.open(dir, RotateFixtures.varlen(1000, 16, 16), 2, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            Appender a = writer.appender();
            Path first = writer.currentPath();

            a.beginRow();
            a.setLong(TS, 1000);
            a.setLong(I64, 10);
            a.setBytes(SYM, buf(16, 'A'), 0, 16);
            a.endRow();

            a.beginRow();
            a.setLong(TS, 2000);
            a.setLong(I64, 20);
            a.setBytes(SYM, buf(5, 'B'), 0, 5);
            a.endRow();

            assertThat(writer.currentPath()).isEqualTo(first);
            try (SnapshotReader reader = SnapshotReader.open(first)) {
                var batches = reader.snapshot().batches();
                assertThat(batches).hasSize(2);
                assertThat(batches.get(0).sealed()).isTrue();
                assertThat(batches.get(0).rowCount()).isEqualTo(1);
                assertThat(batches.get(1).sealed()).isFalse();
                assertThat(batches.get(1).rowCount()).isEqualTo(1);
            }
        }
        assertThat(dir.list()).hasSize(1);
    }

    private static UnsafeBuffer buf(int n, char fill) {
        return new UnsafeBuffer(bytes(n, fill));
    }

    private static byte[] bytes(int n, char fill) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    private static String str(VarCharVector v, int row) {
        return new String(v.get(row), StandardCharsets.UTF_8);
    }
}
