package io.ascham.layout;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.schema.ArenaSchema;
import io.ascham.util.Alignment;
import java.util.ArrayList;
import java.util.List;
import org.agrona.BitUtil;
import org.junit.jupiter.api.Test;

/**
 * Enforces the alignment invariants across many generated schemas: every buffer base is 64-byte
 * aligned (invariant 5), the batch stride is page-aligned (invariant 6), and no two buffers within
 * a batch overlap or spill past the stride.
 */
class LayoutAlignmentTest {

    @Test
    void everyBufferIs64ByteAlignedAndStrideIsPageAligned() {
        for (long seed = 0; seed < 2_000; seed++) {
            ArenaSchema schema = RandomSchemaGenerator.generate(seed);
            LayoutDescriptor d = Layouts.compute(schema);

            assertThat(BitUtil.isAligned(d.batchStrideBytes(), Alignment.PAGE_ALIGN))
                    .as("stride page-aligned, seed %d", seed).isTrue();

            List<long[]> extents = new ArrayList<>(); // {start, endExclusive}
            for (ColumnLayout c : d.columns()) {
                assertAligned(c.validityOffset(), seed);
                assertAligned(c.dataOffset(), seed);
                extents.add(new long[]{c.validityOffset(), c.validityOffset() + Alignment.bitmapBytes(d.batchRows())});
                extents.add(new long[]{c.dataOffset(), c.dataOffset() + c.dataCapacityBytes()});
                if (c.isVarlen()) {
                    assertAligned(c.offsetsOffset(), seed);
                    long offsetsEnd = c.offsetsOffset() + (long) (d.batchRows() + 1) * Integer.BYTES;
                    extents.add(new long[]{c.offsetsOffset(), offsetsEnd});
                } else {
                    assertThat(c.offsetsOffset()).isEqualTo(ColumnLayout.NO_OFFSETS);
                }
            }

            extents.sort((a, b) -> Long.compare(a[0], b[0]));
            long prevEnd = 0;
            for (long[] e : extents) {
                assertThat(e[0]).as("no overlap, seed %d", seed).isGreaterThanOrEqualTo(prevEnd);
                assertThat(e[1]).as("within stride, seed %d", seed).isLessThanOrEqualTo(d.batchStrideBytes());
                prevEnd = e[1];
            }
        }
    }

    private static void assertAligned(long offset, long seed) {
        assertThat(BitUtil.isAligned(offset, Alignment.BUFFER_ALIGN))
                .as("64-byte aligned, seed %d", seed).isTrue();
    }
}
