package io.ascham.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.sun.management.ThreadMXBean;
import io.ascham.rotate.RollCycle;
import io.ascham.rotate.RotatingWriter;
import io.ascham.rotate.SegmentDirectory;
import io.ascham.schema.ArenaSchema;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link AllocationTest} gate, through {@link RotatingWriter#appender()}: the rolling layer —
 * including its per-row policy check — must add zero steady-state allocation on top of the raw
 * append path. This is what enforces the cached day-boundary/context design in RotatingWriter.
 */
class RollingAppenderAllocationTest {

    @TempDir
    Path dir;

    @Test
    void rollingAppendAllocatesZeroBytesSteadyState() {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeThat(bean.isThreadAllocatedMemorySupported()).isTrue();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();

        ArenaSchema schema = WriterFixtures.allTypesSchema(1024);
        UnsafeBuffer buf16 = new UnsafeBuffer(new byte[16]);
        UnsafeBuffer buf8 = new UnsafeBuffer(new byte[8]);
        Clock clock = Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC);

        try (RotatingWriter writer = RotatingWriter.open(new SegmentDirectory(dir, "alltypes"), schema,
                1024, 1L, RollCycle.DAILY, clock, new WriterFixtures.FakeClock(0, 1))) {
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
                    .as("steady-state bytes allocated per rolling append (%d bytes over %d ops)", allocated, ops)
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
}
