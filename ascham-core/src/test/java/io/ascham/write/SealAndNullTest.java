package io.ascham.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.schema.ArenaSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SealAndNullTest {

    @TempDir
    Path dir;

    private static final int TS = 0, FLAG = 1, I64 = 5, SYM = 12, BIN = 13;

    @Test
    void sealsOnRowCount() {
        ArenaSchema schema = WriterFixtures.allTypesSchema(4); // seal every 4 rows
        Path path = dir.resolve("seg.ascham");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 8, 1L, 1L, new WriterFixtures.FakeClock(0, 1))) {
            Appender a = writer.appender();
            for (int r = 0; r < 5; r++) {
                a.beginRow();
                a.setLong(TS, 1000 + r);
                a.setLong(I64, r);
                a.endRow();
            }

            RawSegmentReader reader = new RawSegmentReader(
                    writer.segmentFile(), writer.layoutDescriptor(), writer.regions());
            assertThat(reader.activeBatchCount()).isEqualTo(2);
            assertThat(reader.rowCount(0)).isEqualTo(4);
            assertThat(reader.inProgress(0)).isFalse();
            assertThat(reader.rowCount(1)).isEqualTo(1);
            assertThat(reader.inProgress(1)).isTrue();
            // Batch 1 accumulates in place at capacity stride, one stride past batch 0.
            assertThat(reader.getIntegral(1, 0, I64)).isEqualTo(4);
        }
    }

    @Test
    void nullsLeaveValidityUnsetAndVarlenEmpty() {
        ArenaSchema schema = WriterFixtures.allTypesSchema(8);
        Path path = dir.resolve("seg.ascham");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 4, 1L, 1L, new WriterFixtures.FakeClock(0, 1))) {
            Appender a = writer.appender();

            // Row 0: fully populated (time + stats + varlens).
            a.beginRow();
            a.setLong(TS, 1000);
            a.setLong(I64, 7);
            a.setBool(FLAG, true);
            a.setBytes(SYM, new UnsafeBuffer("MSFT".getBytes(StandardCharsets.UTF_8)), 0, 4);
            a.setBytes(BIN, new UnsafeBuffer(new byte[]{9}), 0, 1);
            a.endRow();

            // Row 1: only the (required) time column set; everything else null.
            a.beginRow();
            a.setLong(TS, 1001);
            a.endRow();
            writer.seal();

            RawSegmentReader reader = new RawSegmentReader(
                    writer.segmentFile(), writer.layoutDescriptor(), writer.regions());
            assertThat(reader.rowCount(0)).isEqualTo(2);

            assertThat(reader.isValid(0, 0, FLAG)).isTrue();
            assertThat(reader.getVarlen(0, 0, SYM)).isEqualTo("MSFT".getBytes(StandardCharsets.UTF_8));

            assertThat(reader.isValid(0, 1, FLAG)).isFalse();
            assertThat(reader.isValid(0, 1, I64)).isFalse();
            assertThat(reader.isValid(0, 1, SYM)).isFalse();
            assertThat(reader.getVarlen(0, 1, SYM)).isEmpty(); // offsets[2] == offsets[1]
            assertThat(reader.isValid(0, 1, TS)).isTrue();

            // Stats reflect both rows' time values; stats column saw only row 0.
            assertThat(reader.tsMin(0)).isEqualTo(1000);
            assertThat(reader.tsMax(0)).isEqualTo(1001);
            assertThat(reader.statMin(0)).isEqualTo(7);
            assertThat(reader.statMax(0)).isEqualTo(7);
        }
    }
}
