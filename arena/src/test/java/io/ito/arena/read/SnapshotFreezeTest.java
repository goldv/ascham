package io.ito.arena.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.nio.file.Path;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotFreezeTest {

    @TempDir
    Path dir;

    @Test
    void snapshotDoesNotSeeRowsAppendedAfterItWasTaken() {
        ArenaSchema schema = ReaderFixtures.tsStatsSchema(64);
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 4, 1L, 1L, new ReaderFixtures.FakeClock(0, 1))) {
            appendRows(writer.genericAppender(), 0, 3);

            try (SnapshotReader reader = SnapshotReader.open(path)) {
                Snapshot frozen = reader.snapshot();
                assertThat(frozen.batches().get(0).rowCount()).isEqualTo(3);

                // Writer keeps appending into the same in-progress batch.
                appendRows(writer.genericAppender(), 3, 5);

                // The frozen snapshot is unchanged; a fresh snapshot sees the new rows.
                assertThat(frozen.batches().get(0).rowCount()).isEqualTo(3);
                assertThat(reader.snapshot().batches().get(0).rowCount()).isEqualTo(5);

                // Reading the frozen view still yields exactly its 3 rows, values intact.
                try (VectorSchemaRoot root = frozen.batches().get(0).root()) {
                    assertThat(root.getRowCount()).isEqualTo(3);
                    BigIntVector i64 = (BigIntVector) root.getVector("i64");
                    assertThat(i64.get(0)).isZero();
                    assertThat(i64.get(2)).isEqualTo(2);
                }
            }
        }
    }

    private static void appendRows(GenericAppender a, int from, int to) {
        for (int r = from; r < to; r++) {
            a.beginRow();
            a.setLong(0, 1000 + r);
            a.setLong(1, r);
            a.endRow();
        }
    }
}
