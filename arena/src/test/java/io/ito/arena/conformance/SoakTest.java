package io.ito.arena.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.BatchView;
import io.ito.arena.read.Snapshot;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.MetadataKeys;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.SystemEpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One writer, N readers, over a single segment. Each row encodes its global index {@code g} in three
 * columns; readers verify every row they see is internally consistent (no torn reads), that total
 * row counts are monotonic, and that a frozen snapshot re-reads identically later. The short
 * {@code smoke} variant runs in the normal suite; the long {@code soak} variant is tagged and run by
 * the {@code soakTest} task.
 */
class SoakTest {

    private static final long BASE_TS = 1_700_000_000_000_000_000L;
    private static final int BATCH_ROWS = 64;
    private static final int MAX_BATCHES = 4096;

    @TempDir
    Path dir;

    @Test
    void smoke() throws Exception {
        run(500, 3);
    }

    @Test
    @Tag("soak")
    void soak() throws Exception {
        long seconds = Long.getLong("io.ito.arena.soak.seconds", 15);
        run(seconds * 1000, 4);
    }

    private void run(long durationMillis, int readerCount) throws Exception {
        Path path = dir.resolve("soak.arena");
        ArenaSchema schema = soakSchema();
        long deadline = System.nanoTime() + durationMillis * 1_000_000L;

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicBoolean writerDone = new AtomicBoolean(false);
        AtomicLong rowsWritten = new AtomicLong();
        CountDownLatch ready = new CountDownLatch(1);

        SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, MAX_BATCHES, 1L, 1L, new SystemEpochNanoClock());
        ready.countDown();

        Thread writerThread = new Thread(() -> {
            try (writer) {
                GenericAppender a = writer.genericAppender();
                long g = 0;
                while (System.nanoTime() < deadline && g < (long) MAX_BATCHES * BATCH_ROWS - BATCH_ROWS) {
                    a.beginRow();
                    a.setLong(0, BASE_TS + g);
                    a.setLong(1, g);
                    byte[] sym = Long.toString(g).getBytes(StandardCharsets.UTF_8);
                    a.setBytes(2, new UnsafeBuffer(sym), 0, sym.length);
                    a.endRow();
                    rowsWritten.incrementAndGet();
                    g++;
                }
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                writerDone.set(true);
            }
        }, "soak-writer");

        List<Thread> readers = new java.util.ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            Thread reader = new Thread(() -> {
                try {
                    ready.await();
                    readerLoop(path, deadline, writerDone);
                } catch (Throwable t) {
                    failures.add(t);
                }
            }, "soak-reader-" + i);
            readers.add(reader);
        }

        writerThread.start();
        readers.forEach(Thread::start);
        writerThread.join();
        for (Thread reader : readers) {
            reader.join();
        }

        assertThat(failures).as("no writer/reader failures").isEmpty();
        assertThat(rowsWritten.get()).as("writer made progress").isPositive();
    }

    private void readerLoop(Path path, long deadline, AtomicBoolean writerDone) throws Exception {
        try (SnapshotReader reader = SnapshotReader.open(path)) {
            long maxTotal = 0;
            boolean stabilityChecked = false;
            // Keep going until the writer is done AND we've read at least the final state once.
            while (System.nanoTime() < deadline || !writerDone.get() || maxTotal == 0) {
                Snapshot snapshot = reader.snapshot();
                long total = verify(snapshot);
                assertThat(total).as("row count is monotonic").isGreaterThanOrEqualTo(maxTotal);
                maxTotal = total;

                if (!stabilityChecked && total > 0) {
                    // A frozen snapshot re-reads identically after the writer has moved on.
                    long again = verify(snapshot);
                    assertThat(again).as("frozen snapshot is stable").isEqualTo(total);
                    stabilityChecked = true;
                }
                if (writerDone.get() && System.nanoTime() >= deadline) {
                    break;
                }
            }
        }
    }

    /** Verifies every row is internally consistent and returns the total row count. */
    private long verify(Snapshot snapshot) {
        long total = 0;
        for (BatchView view : snapshot.batches()) {
            int rows = view.rowCount();
            total += rows;
            if (rows == 0) {
                continue;
            }
            try (VectorSchemaRoot root = view.root()) {
                TimeStampNanoTZVector ts = (TimeStampNanoTZVector) root.getVector("ts");
                BigIntVector i64 = (BigIntVector) root.getVector("i64");
                VarCharVector sym = (VarCharVector) root.getVector("sym");
                for (int r = 0; r < rows; r++) {
                    long g = i64.get(r);
                    assertThat(ts.get(r)).as("ts matches encoded index").isEqualTo(BASE_TS + g);
                    assertThat(new String(sym.get(r), StandardCharsets.UTF_8))
                            .as("varlen matches encoded index").isEqualTo(Long.toString(g));
                }
            }
        }
        return total;
    }

    private static ArenaSchema soakSchema() {
        List<Field> fields = List.of(
                field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                field("i64", new ArrowType.Int(64, true)),
                field("sym", new ArrowType.Utf8(), Map.of(MetadataKeys.VARLEN_BYTES, "2048")));
        return ArenaSchema.load(new Schema(fields, Map.of(
                MetadataKeys.TABLE, "soak",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "i64",
                MetadataKeys.BATCH_ROWS, Integer.toString(BATCH_ROWS))));
    }

    private static Field field(String name, ArrowType type) {
        return field(name, type, Map.of());
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }
}
