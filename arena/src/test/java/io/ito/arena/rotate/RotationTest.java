package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.read.Snapshot;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.Appender;
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
            RotateFixtures.append(writer, 1000, 1);
            RotateFixtures.append(writer, 1001, 2);

            clock.set(Instant.parse("2026-07-29T09:00:00Z")); // next UTC day
            RotateFixtures.append(writer, 2000, 3);
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
                RotateFixtures.append(writer, 1000 + r, r);
            }
        }

        List<SegmentDirectory.SegmentName> segments = dir.list();
        assertThat(segments).hasSize(2); // same day, sequence 0 then 1
        assertThat(segments.get(0).path().getFileName()).hasToString("20260728.0.arena");
        assertThat(segments.get(1).path().getFileName()).hasToString("20260728.1.arena");
        // All 5 rows are preserved across the rotation.
        assertThat(totalRows(segments.get(0).path()) + totalRows(segments.get(1).path())).isEqualTo(5);
    }

    @Test
    void heartbeatMidRowDefersRotationToNextBeginRow() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, RotateFixtures.tsStats(64), 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            RotateFixtures.append(writer, 1000, 1);
            Appender a = writer.appender();
            a.beginRow();
            a.setLong(0, 2000);

            clock.set(Instant.parse("2026-07-29T09:00:00Z")); // day turns mid-row
            Path before = writer.currentPath();
            writer.heartbeat(); // must not rotate under an open row
            assertThat(writer.currentPath()).isEqualTo(before);

            a.setLong(1, 2);
            a.endRow();
            RotateFixtures.append(writer, 3000, 3); // the deferred rotation fires here
            assertThat(writer.currentPath()).isNotEqualTo(before);
            assertThat(totalRows(before)).isEqualTo(2);
            assertThat(totalRows(writer.currentPath())).isEqualTo(1);
        }
    }

    @Test
    void forcedRotationMidRowIsRejected() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, RotateFixtures.tsStats(64), 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            Appender a = writer.appender();
            a.beginRow();
            assertThatThrownBy(writer::rotate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mid-row");
            a.setLong(0, 1000);
            a.setLong(1, 1);
            a.endRow();
            writer.rotate(); // fine once the row is closed
            assertThat(dir.list()).hasSize(2);
        }
    }

    @Test
    void closeDiscardsAnOpenRow() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));
        Path path;

        try (RotatingWriter writer = RotatingWriter.open(dir, RotateFixtures.tsStats(64), 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            RotateFixtures.append(writer, 1000, 1);
            path = writer.currentPath();
            Appender a = writer.appender();
            a.beginRow();
            a.setLong(0, 2000); // never ended: was never published, so close drops it
        }

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            var b0 = reader.snapshot().batches().get(0);
            assertThat(b0.sealed()).isTrue();
            assertThat(b0.rowCount()).isEqualTo(1);
            assertThat(b0.tsMax()).isEqualTo(1000);
        }
    }

    private static int totalRows(Path segment) {
        try (SnapshotReader reader = SnapshotReader.open(segment)) {
            Snapshot snapshot = reader.snapshot();
            return snapshot.batches().stream().mapToInt(v -> v.rowCount()).sum();
        }
    }
}
