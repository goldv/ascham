package io.ito.arena.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenericAppenderTest {

    @TempDir
    Path dir;

    // Column ordinals in WriterFixtures.allTypesSchema.
    private static final int TS = 0, FLAG = 1, I8 = 2, U16 = 3, I32 = 4, I64 = 5,
            F32 = 6, F64 = 7, DEC = 8, D32 = 9, T64 = 10, FSB = 11, SYM = 12, BIN = 13;

    @Test
    void everyTypeRoundTripsThroughRawBuffers() {
        ArenaSchema schema = WriterFixtures.allTypesSchema(8);
        long start = 1_750_000_000_000_000_000L;
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 4, 42L, 1L, new WriterFixtures.FakeClock(start, 1000))) {
            GenericAppender a = writer.genericAppender();

            for (int r = 0; r < 3; r++) {
                a.beginRow();
                a.setLong(TS, 1000 + r);
                a.setBool(FLAG, r % 2 == 0);
                a.setByte(I8, (byte) (r - 1));
                a.setShort(U16, (short) (100 + r));
                a.setInt(I32, 100_000 + r);
                a.setLong(I64, 5_000_000_000L + r);
                a.setFloat(F32, 1.5f + r);
                a.setDouble(F64, 2.5 + r);
                a.setDecimal128(DEC, 0x1122334455667788L, r);
                a.setInt(D32, 19_000 + r);
                a.setLong(T64, 123_456_789L + r);
                a.setFixedBytes(FSB, buf(fixed16(r)), 0, 16);
                a.setBytes(SYM, buf(("AAPL" + r).getBytes(StandardCharsets.UTF_8)), 0, ("AAPL" + r).length());
                byte[] bin = {(byte) r, (byte) (r + 1)};
                a.setBytes(BIN, buf(bin), 0, bin.length);
                a.endRow();
            }
            writer.seal();

            RawSegmentReader reader = new RawSegmentReader(
                    writer.segmentFile(), writer.layoutDescriptor(), writer.regions());
            assertThat(reader.rowCount(0)).isEqualTo(3);
            assertThat(reader.inProgress(0)).isFalse();

            for (int r = 0; r < 3; r++) {
                assertThat(reader.isValid(0, r, TS)).isTrue();
                assertThat(reader.getIntegral(0, r, TS)).isEqualTo(1000 + r);
                assertThat(reader.getBool(0, r, FLAG)).isEqualTo(r % 2 == 0);
                assertThat(reader.getIntegral(0, r, I8)).isEqualTo(r - 1);
                assertThat(reader.getIntegral(0, r, U16) & 0xFFFF).isEqualTo(100 + r);
                assertThat(reader.getIntegral(0, r, I32)).isEqualTo(100_000 + r);
                assertThat(reader.getIntegral(0, r, I64)).isEqualTo(5_000_000_000L + r);
                assertThat(reader.getFloat(0, r, F32)).isEqualTo(1.5f + r);
                assertThat(reader.getDouble(0, r, F64)).isEqualTo(2.5 + r);
                assertThat(reader.getDecimal128(0, r, DEC)).containsExactly(0x1122334455667788L, r);
                assertThat(reader.getIntegral(0, r, D32)).isEqualTo(19_000 + r);
                assertThat(reader.getIntegral(0, r, T64)).isEqualTo(123_456_789L + r);
                assertThat(reader.getFixedBytes(0, r, FSB)).isEqualTo(fixed16(r));
                assertThat(reader.getVarlen(0, r, SYM)).isEqualTo(("AAPL" + r).getBytes(StandardCharsets.UTF_8));
                assertThat(reader.getVarlen(0, r, BIN)).containsExactly((byte) r, (byte) (r + 1));
            }

            // Catalog stats come from the time and stats columns.
            assertThat(reader.tsMin(0)).isEqualTo(1000);
            assertThat(reader.tsMax(0)).isEqualTo(1002);
            assertThat(reader.statMin(0)).isEqualTo(5_000_000_000L);
            assertThat(reader.statMax(0)).isEqualTo(5_000_000_002L);
            assertThat(reader.sealNanos(0)).isEqualTo(start); // first clock read
        }
    }

    private static byte[] fixed16(int r) {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) (r * 16 + i);
        }
        return b;
    }

    private static UnsafeBuffer buf(byte[] bytes) {
        return new UnsafeBuffer(bytes);
    }
}
