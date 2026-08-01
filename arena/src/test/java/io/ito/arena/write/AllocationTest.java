package io.ito.arena.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.sun.management.ThreadMXBean;
import io.ito.arena.schema.ArenaSchema;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the append hot path is allocation-free in steady state (spec: "Append operations must
 * allocate zero bytes steady-state"). Measured directly with {@link ThreadMXBean#getThreadAllocatedBytes}
 * — more deterministic as a CI gate than a forked JMH GC-profiler run, and it measures exactly the
 * invariant. Inputs (the {@code DirectBuffer} slices for varlen/fixed-binary columns) are
 * preallocated so only the appender's own allocation is counted.
 */
class AllocationTest {

    @TempDir
    Path dir;

    @Test
    void appendAllocatesZeroBytesSteadyState() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeThat(bean.isThreadAllocatedMemorySupported()).isTrue();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();

        ArenaSchema schema = WriterFixtures.allTypesSchema(1024);
        UnsafeBuffer buf16 = new UnsafeBuffer(new byte[16]);
        UnsafeBuffer buf8 = new UnsafeBuffer(new byte[8]);

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path(), schema, 1024, 1L, 1L, new WriterFixtures.FakeClock(0, 1))) {
            Appender a = writer.appender();

            // Warm up: JIT the append path and cross several seal boundaries.
            for (int r = 0; r < 300_000; r++) {
                writeRow(a, r, buf16, buf8);
            }

            int ops = 200_000;
            long before = bean.getThreadAllocatedBytes(tid);
            for (int r = 0; r < ops; r++) {
                writeRow(a, r, buf16, buf8);
            }
            long allocated = bean.getThreadAllocatedBytes(tid) - before;

            double bytesPerOp = (double) allocated / ops;
            assertThat(bytesPerOp)
                    .as("steady-state bytes allocated per append (%d bytes over %d ops)", allocated, ops)
                    .isLessThan(1.0);
        }
    }

    private static void writeRow(Appender a, int r, UnsafeBuffer buf16, UnsafeBuffer buf8) {
        a.beginRow();
        a.setLong(0, r);
        a.setBool(1, (r & 1) == 0);
        a.setByte(2, (byte) r);
        a.setShort(3, (short) r);
        a.setInt(4, r);
        a.setLong(5, r);
        a.setFloat(6, r);
        a.setDouble(7, r);
        a.setDecimal128(8, r, 0);
        a.setInt(9, r);
        a.setLong(10, r);
        a.setFixedBytes(11, buf16, 0, 16);
        a.setBytes(12, buf8, 0, 8);
        a.setBytes(13, buf8, 0, 8);
        a.endRow();
    }

    private Path path() {
        return dir.resolve("alloc.arena");
    }
}
