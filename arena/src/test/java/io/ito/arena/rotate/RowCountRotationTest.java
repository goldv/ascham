package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Capacity rotation is decided at {@code beginRow}, before anything is written: no
 * {@code SegmentFullException}, no row replay, and the retired segment is left fully sealed.
 */
class RowCountRotationTest {

    @TempDir
    Path base;

    @Test
    void beginRowRotatesExactlyAtCapacityWithoutThrowing() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        // maxBatches=2, batchRows=2 → the segment holds exactly 4 rows.
        ArenaSchema schema = RotateFixtures.tsStats(2);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 2, 1L,
                RollCycle.DAILY, clock, RotateFixtures.counterNanoClock())) {
            for (int r = 0; r < 4; r++) {
                RotateFixtures.append(writer, 1000 + r, r);
            }
            Path first = writer.currentPath();

            RotateFixtures.append(writer, 2000, 9); // 5th row: rotation happens inside beginRow
            Path second = writer.currentPath();
            assertThat(second).isNotEqualTo(first);

            // Retired segment: both batches sealed with published stats, 2 rows each.
            try (SnapshotReader reader = SnapshotReader.open(first)) {
                var batches = reader.snapshot().batches();
                assertThat(batches).hasSize(2);
                assertThat(batches).allSatisfy(b -> {
                    assertThat(b.sealed()).isTrue();
                    assertThat(b.rowCount()).isEqualTo(2);
                });
                assertThat(batches.get(0).tsMin()).isEqualTo(1000);
                assertThat(batches.get(1).tsMax()).isEqualTo(1003);
            }
            // The 5th row landed in the successor's batch 0, row 0, still in progress.
            try (SnapshotReader reader = SnapshotReader.open(second)) {
                var batches = reader.snapshot().batches();
                assertThat(batches).hasSize(1);
                assertThat(batches.get(0).sealed()).isFalse();
                assertThat(batches.get(0).rowCount()).isEqualTo(1);
            }
        }

        var segments = dir.list();
        assertThat(segments).hasSize(2); // same day, sequence advanced
        assertThat(segments.get(0).path().getFileName()).hasToString("20260728.0.arena");
        assertThat(segments.get(1).path().getFileName()).hasToString("20260728.1.arena");
    }
}
