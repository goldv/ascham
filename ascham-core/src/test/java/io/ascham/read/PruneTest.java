package io.ascham.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.schema.ArenaSchema;
import io.ascham.write.Appender;
import io.ascham.write.SegmentWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PruneTest {

    @TempDir
    Path dir;

    @Test
    void prunesSealedBatchesButNeverInProgressOnes() {
        // batchRows=2 seals every 2 rows: batch0 [ts 100,101], batch1 [ts 200,201],
        // batch2 in-progress [ts 300]. Stats column tracks i64.
        ArenaSchema schema = ReaderFixtures.tsStatsSchema(2);
        Path path = dir.resolve("seg.ascham");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 8, 1L, 1L, new ReaderFixtures.FakeClock(0, 1))) {
            Appender a = writer.appender();
            append(a, 100, 10);
            append(a, 101, 11);
            append(a, 200, 20);
            append(a, 201, 21);
            append(a, 300, 30);
        }

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            Snapshot snapshot = reader.snapshot();
            assertThat(snapshot.batchCount()).isEqualTo(3);
            assertThat(snapshot.batches().get(2).sealed()).isFalse();

            // Time filter selects batch1; in-progress batch2 always survives.
            assertThat(indexes(snapshot.prune(new TimeRange(150, 250), null))).containsExactly(1, 2);
            // Stat filter selects batch0; batch2 survives.
            assertThat(indexes(snapshot.prune(null, new StatRange(10, 15)))).containsExactly(0, 2);
            // Nothing sealed matches; only the in-progress batch survives.
            assertThat(indexes(snapshot.prune(new TimeRange(1000, 2000), new StatRange(1000, 2000))))
                    .containsExactly(2);
            // No filter: everything.
            assertThat(indexes(snapshot.prune(null, null))).containsExactly(0, 1, 2);
        }
    }

    private static void append(Appender a, long ts, long stat) {
        a.beginRow();
        a.setLong(0, ts);
        a.setLong(1, stat);
        a.endRow();
    }

    private static List<Integer> indexes(List<BatchView> views) {
        return views.stream().map(BatchView::batchIndex).toList();
    }
}
