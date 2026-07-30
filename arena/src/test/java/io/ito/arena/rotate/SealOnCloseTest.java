package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.BatchView;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2: a segment the writer is finished with (rotated away, or closed at shutdown) must have every
 * row-bearing batch sealed with published zone-map stats.
 *
 * <p>Why this matters beyond tidiness: an unsealed batch is indistinguishable from one a live writer
 * is still filling, and its {@code ts_min/ts_max} are unpublished zeros. That blinds zone-map
 * pruning for the batch and makes it impossible for the cold-tier roller to verify that a day's
 * segments only contain that day's rows (see docs/cold-tier-design-plan.md §3, invariant I2).
 */
class SealOnCloseTest {

    @TempDir
    Path base;

    @Test
    void rotatedAwaySegmentHasEveryRowBearingBatchSealed() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(2); // seals every 2 rows
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        Path first;
        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            // 3 rows: batch0 seals on row count, batch1 holds the 3rd row and is still in progress.
            writer.append(RotateFixtures.row(1000, 10));
            writer.append(RotateFixtures.row(1001, 11));
            writer.append(RotateFixtures.row(1002, 12));
            first = writer.currentPath();
            writer.rotate();
        }

        try (SnapshotReader reader = SnapshotReader.open(first)) {
            List<BatchView> batches = reader.snapshot().batches();
            // batch0 filled and sealed on row count; batch1 holds the 3rd row. A successor batch is
            // only opened when the next row arrives, so there is no trailing empty batch here.
            assertThat(batches).extracting(BatchView::rowCount).containsExactly(2, 1);

            // The trailing partial batch is sealed by rotation...
            BatchView trailing = batches.get(1);
            assertThat(trailing.sealed()).isTrue();
            // ...with real stats, not the zeros an unsealed batch carries.
            assertThat(trailing.tsMin()).isEqualTo(1002);
            assertThat(trailing.tsMax()).isEqualTo(1002);
            assertThat(trailing.statMin()).isEqualTo(12);
            assertThat(trailing.statMax()).isEqualTo(12);
        }
    }

    @Test
    void gracefulShutdownSealsTheFinalSegmentToo() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64); // no row-count seal will fire
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        Path only;
        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(2000, 20));
            writer.append(RotateFixtures.row(2001, 21));
            only = writer.currentPath();
        } // close() seals

        try (SnapshotReader reader = SnapshotReader.open(only)) {
            BatchView batch = reader.snapshot().batches().get(0);
            assertThat(batch.rowCount()).isEqualTo(2);
            assertThat(batch.sealed()).isTrue();
            assertThat(batch.tsMin()).isEqualTo(2000);
            assertThat(batch.tsMax()).isEqualTo(2001);
        }
    }

    @Test
    void everyRowSurvivesSealingAndStatsCoverThemAll() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(4);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        Path path;
        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 16, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            for (int r = 0; r < 10; r++) { // 4 + 4 + 2, so the trailing batch is partial
                writer.append(RotateFixtures.row(5000 + r, r));
            }
            path = writer.currentPath();
        }

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            List<BatchView> batches = reader.snapshot().batches();
            assertThat(batches.stream().mapToInt(BatchView::rowCount).sum()).isEqualTo(10);
            // Sealing must not lose or duplicate rows, and the union of sealed batch ranges must
            // cover every appended timestamp — the property the roller's day-alignment check relies on.
            long lo = batches.stream().filter(b -> b.rowCount() > 0).mapToLong(BatchView::tsMin).min().orElseThrow();
            long hi = batches.stream().filter(b -> b.rowCount() > 0).mapToLong(BatchView::tsMax).max().orElseThrow();
            assertThat(lo).isEqualTo(5000);
            assertThat(hi).isEqualTo(5009);
            assertThat(batches.stream().filter(b -> b.rowCount() > 0)).allMatch(BatchView::sealed);
        }
    }

    @Test
    void anEmptyTrailingBatchIsLeftInProgress() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        Path path;
        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(4000, 40));
            writer.current().seal(); // seals batch0 and opens an empty batch1
            path = writer.currentPath();
        }

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            List<BatchView> batches = reader.snapshot().batches();
            assertThat(batches).extracting(BatchView::rowCount).containsExactly(1, 0);
            assertThat(batches.get(0).sealed()).isTrue();
            // Left unsealed on purpose: sealing would publish a meaningless [0,0] range, and every
            // consumer already skips zero-row batches.
            assertThat(batches.get(1).sealed()).isFalse();
        }
    }

    @Test
    void closeIsIdempotentAfterSealing() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock());
        writer.append(RotateFixtures.row(3000, 30));
        Path path = writer.currentPath();
        writer.close();
        writer.close(); // must not re-seal or touch the unmapped buffer

        try (SnapshotReader reader = SnapshotReader.open(path)) {
            assertThat(reader.snapshot().batches().get(0).sealed()).isTrue();
        }
    }
}
