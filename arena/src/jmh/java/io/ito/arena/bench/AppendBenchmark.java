package io.ito.arena.bench;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Append throughput. {@code appendRow} measures a full 14-column row (every physical kind);
 * {@code appendBatchAndSeal} measures a filled batch plus its seal, so seal latency is captured.
 * The writer is recreated when the segment nears capacity so file growth doesn't dominate.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class AppendBenchmark {

    private static final int BATCH_ROWS = 4096;
    private static final int MAX_BATCHES = 512;
    private static final long LIMIT = (long) (MAX_BATCHES - 1) * BATCH_ROWS;

    private Path dir;
    private SegmentWriter writer;
    private GenericAppender appender;
    private final UnsafeBuffer buf16 = new UnsafeBuffer(new byte[16]);
    private final UnsafeBuffer buf8 = new UnsafeBuffer(new byte[8]);
    private long row;
    private long seq;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        dir = Files.createTempDirectory("append-bench");
        openWriter();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        writer.close();
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @Benchmark
    public void appendRow() {
        if (row >= LIMIT) {
            recycle();
        }
        writeRow(appender, (int) row++, buf16, buf8);
    }

    @Benchmark
    public void appendBatchAndSeal() {
        if (row >= LIMIT - BATCH_ROWS) {
            recycle();
        }
        for (int i = 0; i < BATCH_ROWS; i++) {
            writeRow(appender, (int) row++, buf16, buf8);
        }
        writer.seal();
    }

    private void recycle() {
        writer.close();
        openWriter();
    }

    private void openWriter() {
        ArenaSchema schema = BenchSupport.allTypes(BATCH_ROWS);
        writer = SegmentWriter.createSegment(
                dir.resolve("s-" + (seq++) + ".arena"), schema, MAX_BATCHES, 1L, seq, BenchSupport.counterClock());
        appender = writer.genericAppender();
        row = 0;
    }

    private static void writeRow(GenericAppender a, int r, UnsafeBuffer buf16, UnsafeBuffer buf8) {
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
