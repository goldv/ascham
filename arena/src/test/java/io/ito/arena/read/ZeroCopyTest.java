package io.ito.arena.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the {@code VectorSchemaRoot} is a true zero-copy view: every vector buffer's memory address
 * lies inside the mapped segment, so Arrow reads the arena bytes directly rather than a copy.
 */
class ZeroCopyTest {

    @TempDir
    Path dir;

    @Test
    void vectorBuffersPointIntoTheMappedSegment() {
        ArenaSchema schema = ReaderFixtures.allTypesSchema(64);
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 4, 1L, 1L, new ReaderFixtures.FakeClock(0, 1))) {
            GenericAppender a = writer.genericAppender();
            a.beginRow();
            a.setLong(0, 1000);
            a.setLong(5, 42);
            a.setBytes(12, new UnsafeBuffer("HELLO".getBytes(StandardCharsets.UTF_8)), 0, 5);
            a.endRow();
            writer.seal();
        }

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            long base = reader.file().data().addressOffset();
            long end = base + reader.file().size();

            BatchView view = reader.snapshot().batches().get(0);
            try (VectorSchemaRoot root = view.root()) {
                int checked = 0;
                for (FieldVector vector : root.getFieldVectors()) {
                    for (ArrowBuf buffer : vector.getBuffers(false)) {
                        if (buffer.capacity() > 0) {
                            long address = buffer.memoryAddress();
                            assertThat(address)
                                    .as("buffer of %s points into the mapping", vector.getName())
                                    .isBetween(base, end - 1);
                            checked++;
                        }
                    }
                }
                assertThat(checked).as("some non-empty buffers were verified").isPositive();
            }
        }
    }
}
