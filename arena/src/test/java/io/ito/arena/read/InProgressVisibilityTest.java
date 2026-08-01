package io.ito.arena.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.Appender;
import io.ito.arena.write.SegmentWriter;
import java.nio.file.Path;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InProgressVisibilityTest {

    @TempDir
    Path dir;

    @Test
    void readerSeesRowsInTheUnsealedInProgressBatch() {
        ArenaSchema schema = ReaderFixtures.tsStatsSchema(64);
        Path path = dir.resolve("seg.arena");

        // Append rows but never seal — freshness comes from readers seeing the in-progress batch.
        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 4, 1L, 1L, new ReaderFixtures.FakeClock(0, 1))) {
            Appender a = writer.appender();
            for (int r = 0; r < 4; r++) {
                a.beginRow();
                a.setLong(0, 5000 + r);
                a.setLong(1, r * 10);
                a.endRow();
            }
        }

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            Snapshot snapshot = reader.snapshot();
            assertThat(snapshot.batchCount()).isEqualTo(1);
            BatchView view = snapshot.batches().get(0);
            assertThat(view.rowCount()).isEqualTo(4);
            assertThat(view.sealed()).isFalse();

            try (VectorSchemaRoot root = view.root()) {
                TimeStampNanoTZVector ts = (TimeStampNanoTZVector) root.getVector("ts");
                BigIntVector i64 = (BigIntVector) root.getVector("i64");
                for (int r = 0; r < 4; r++) {
                    assertThat(ts.get(r)).isEqualTo(5000 + r);
                    assertThat(i64.get(r)).isEqualTo(r * 10L);
                }
            }
        }
    }
}
