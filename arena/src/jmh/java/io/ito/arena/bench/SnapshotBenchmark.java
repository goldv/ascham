package io.ito.arena.bench;

import io.ito.arena.read.Snapshot;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Snapshot construction cost as a function of the number of sealed batches — the reader acquire-loads
 * and freezes every catalog entry once, so cost scales with batch count.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SnapshotBenchmark {

    private static final int BATCH_ROWS = 32;

    @Param({"16", "256", "4096"})
    public int batchCount;

    private Path dir;
    private SnapshotReader reader;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        dir = Files.createTempDirectory("snapshot-bench");
        Path path = dir.resolve("seg.arena");
        ArenaSchema schema = BenchSupport.tsStats(BATCH_ROWS);
        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, batchCount + 1, 1L, 1L, BenchSupport.counterClock())) {
            GenericAppender a = writer.genericAppender();
            for (int b = 0; b < batchCount; b++) {
                for (int r = 0; r < BATCH_ROWS; r++) {
                    a.beginRow();
                    a.setLong(0, (long) b * BATCH_ROWS + r);
                    a.setLong(1, r);
                    a.endRow();
                }
                writer.seal();
            }
        }
        reader = SnapshotReader.open(path);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        reader.close();
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
    public Snapshot snapshot() {
        return reader.snapshot();
    }
}
