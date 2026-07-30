package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.Snapshot;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RotationTest {

    @TempDir
    Path base;

    @Test
    void rotatesOnUtcDayBoundary() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(1000, 1));
            writer.append(RotateFixtures.row(1001, 2));

            clock.set(Instant.parse("2026-07-29T09:00:00Z")); // next UTC day
            writer.append(RotateFixtures.row(2000, 3));
        }

        List<SegmentDirectory.SegmentName> segments = dir.list();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).path().getFileName()).hasToString("20260728.0.arena");
        assertThat(segments.get(1).path().getFileName()).hasToString("20260729.0.arena");
        assertThat(totalRows(segments.get(0).path())).isEqualTo(2);
        assertThat(totalRows(segments.get(1).path())).isEqualTo(1);
    }

    @Test
    void rotatesOnCapacityExhaustion() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        // maxBatches=2, batchRows=2 → a segment holds 4 rows before the 5th append overflows.
        ArenaSchema schema = RotateFixtures.tsStats(2);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 2, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            for (int r = 0; r < 5; r++) {
                writer.append(RotateFixtures.row(1000 + r, r));
            }
        }

        List<SegmentDirectory.SegmentName> segments = dir.list();
        assertThat(segments).hasSize(2); // same day, sequence 0 then 1
        assertThat(segments.get(0).path().getFileName()).hasToString("20260728.0.arena");
        assertThat(segments.get(1).path().getFileName()).hasToString("20260728.1.arena");
        // All 5 rows are preserved across the rotation.
        assertThat(totalRows(segments.get(0).path()) + totalRows(segments.get(1).path())).isEqualTo(5);
    }

    private static int totalRows(Path segment) {
        try (SnapshotReader reader = SnapshotReader.open(segment)) {
            Snapshot snapshot = reader.snapshot();
            return snapshot.batches().stream().mapToInt(v -> v.rowCount()).sum();
        }
    }
}
