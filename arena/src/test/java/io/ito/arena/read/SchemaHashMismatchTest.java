package io.ito.arena.read;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.segment.SegmentFormatException;
import io.ito.arena.write.SegmentWriter;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaHashMismatchTest {

    @TempDir
    Path dir;

    @Test
    void corruptedSchemaFailsHardAtOpen() throws Exception {
        Path path = createSegment();
        // Flip a byte in the embedded schema region (starts at HEADER_LENGTH = 4096).
        corruptByte(path, 4096);

        assertThatThrownBy(() -> SnapshotReader.open(path))
                .isInstanceOf(SegmentFormatException.class)
                .hasMessageContaining("schema hash mismatch");
    }

    @Test
    void corruptedMagicFailsHardAtOpen() throws Exception {
        Path path = createSegment();
        corruptByte(path, 0); // magic byte

        assertThatThrownBy(() -> SnapshotReader.open(path))
                .isInstanceOf(SegmentFormatException.class)
                .hasMessageContaining("magic");
    }

    private Path createSegment() {
        ArenaSchema schema = ReaderFixtures.tsStatsSchema(64);
        Path path = dir.resolve("seg.arena");
        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 4, 1L, 1L, new ReaderFixtures.FakeClock(0, 1))) {
            var a = writer.genericAppender();
            a.beginRow();
            a.setLong(0, 1000);
            a.setLong(1, 1);
            a.endRow();
        }
        return path;
    }

    private static void corruptByte(Path path, long offset) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(offset);
            int b = raf.read();
            raf.seek(offset);
            raf.write(b ^ 0xFF);
        }
    }
}
