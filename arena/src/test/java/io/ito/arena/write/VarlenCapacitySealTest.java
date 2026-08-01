package io.ito.arena.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VarlenCapacitySealTest {

    @TempDir
    Path dir;

    // WriterFixtures.varlenSchema ordinals: ts=0, i64=1, sym=2.
    private static final int TS = 0, I64 = 1, SYM = 2;

    @Test
    void exhaustionSealsAndMigratesOpenRowWithoutRewind() {
        // Big row-count budget so only the varlen byte cap can bind; sym capacity = 16 bytes.
        ArenaSchema schema = WriterFixtures.varlenSchema(1000, 16);
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 8, 1L, 1L, new WriterFixtures.FakeClock(0, 1))) {
            Appender a = writer.appender();

            // Row 0 fills the varlen buffer exactly (16 bytes).
            a.beginRow();
            a.setLong(TS, 1000);
            a.setLong(I64, 10);
            a.setBytes(SYM, new UnsafeBuffer(bytes(16, (byte) 'A')), 0, 16);
            a.endRow();

            // Row 1: ts + i64 written into batch 0, then a 5-byte sym overflows the 16-byte cap,
            // forcing a seal of batch 0 (1 row) and migration of the open row into batch 1 row 0.
            a.beginRow();
            a.setLong(TS, 2000);
            a.setLong(I64, 20);
            a.setBytes(SYM, new UnsafeBuffer(bytes(5, (byte) 'B')), 0, 5);
            a.endRow();

            RawSegmentReader reader = new RawSegmentReader(
                    writer.segmentFile(), writer.layoutDescriptor(), writer.regions());

            assertThat(reader.activeBatchCount()).isEqualTo(2);

            // Batch 0: sealed, 1 row, sym = 16 'A's. Its stats saw only row 0 (deferred-stats design).
            assertThat(reader.rowCount(0)).isEqualTo(1);
            assertThat(reader.inProgress(0)).isFalse();
            assertThat(reader.getVarlen(0, 0, SYM)).isEqualTo(bytes(16, (byte) 'A'));
            assertThat(reader.tsMin(0)).isEqualTo(1000);
            assertThat(reader.tsMax(0)).isEqualTo(1000);

            // Batch 1: in progress, 1 row — the migrated open row, with its fixed cells preserved
            // and the varlen data restarted at offset 0.
            assertThat(reader.rowCount(1)).isEqualTo(1);
            assertThat(reader.inProgress(1)).isTrue();
            assertThat(reader.getIntegral(1, 0, TS)).isEqualTo(2000);
            assertThat(reader.getIntegral(1, 0, I64)).isEqualTo(20);
            assertThat(reader.isValid(1, 0, I64)).isTrue();
            assertThat(reader.getVarlen(1, 0, SYM)).isEqualTo(bytes(5, (byte) 'B'));
        }
    }

    @Test
    void valueLargerThanColumnCapacityIsRejected() {
        ArenaSchema schema = WriterFixtures.varlenSchema(1000, 16);
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 8, 1L, 1L, new WriterFixtures.FakeClock(0, 1))) {
            Appender a = writer.appender();
            a.beginRow();
            a.setLong(TS, 1000);
            a.setLong(I64, 1);
            assertThatThrownBy(() -> a.setBytes(SYM, new UnsafeBuffer(bytes(17, (byte) 'X')), 0, 17))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds column");
        }
    }

    private static byte[] bytes(int n, byte value) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, value);
        return b;
    }
}
