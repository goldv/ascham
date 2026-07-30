package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2: a live-but-idle writer still rotates at the day boundary, because {@link
 * RotatingWriter#heartbeat()} evaluates the rotation policy.
 *
 * <p>Rotation is otherwise only checked inside {@code append}, so a quiet table would hold
 * yesterday's segment open indefinitely. The cold-tier roller cannot archive the newest segment of a
 * live writer — it may still be appended to — so yesterday's rows would stay unarchived until the
 * next row happened to arrive (docs/cold-tier-design-plan.md §4 step 2, §8.1).
 */
class IdleRotationTest {

    @TempDir
    Path base;

    @Test
    void heartbeatRotatesAcrossTheDayBoundaryWithNoAppends() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T23:59:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(1000, 1));
            Path yesterday = writer.currentPath();
            assertThat(yesterday.getFileName()).hasToString("20260728.0.arena");

            writer.heartbeat();
            assertThat(dir.list()).hasSize(1); // same day: no rotation

            // Midnight passes; the producer is idle and only heartbeats.
            clock.set(Instant.parse("2026-07-29T00:00:30Z"));
            writer.heartbeat();

            assertThat(writer.currentPath().getFileName()).hasToString("20260729.0.arena");
            assertThat(dir.list()).extracting(s -> s.path().getFileName().toString())
                    .containsExactly("20260728.0.arena", "20260729.0.arena");

            // And the rotated-away segment is fully sealed, so the roller can archive it at once.
            try (SnapshotReader reader = SnapshotReader.open(yesterday)) {
                assertThat(reader.snapshot().batches().get(0).sealed()).isTrue();
                assertThat(reader.snapshot().batches().get(0).tsMin()).isEqualTo(1000);
            }
        }
    }

    @Test
    void heartbeatStillAdvancesLivenessWhenNoRotationIsDue() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(1000, 1));
            Path path = writer.currentPath();

            long before;
            try (SnapshotReader reader = SnapshotReader.open(path)) {
                before = reader.heartbeat();
            }
            writer.heartbeat();
            try (SnapshotReader reader = SnapshotReader.open(path)) {
                assertThat(reader.heartbeat()).isGreaterThan(before);
            }
        }
    }
}
