package io.ascham.rotate;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.read.SnapshotReader;
import io.ascham.schema.ArenaSchema;
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
                RollCycle.DAILY, clock, RotateFixtures.counterNanoClock())) {
            RotateFixtures.append(writer, 1000, 1);
            Path yesterday = writer.currentPath();
            assertThat(yesterday.getFileName()).hasToString("20260728.0.ascham");

            writer.heartbeat();
            assertThat(dir.list()).hasSize(1); // same day: no rotation

            // Midnight passes; the producer is idle and only heartbeats.
            clock.set(Instant.parse("2026-07-29T00:00:30Z"));
            writer.heartbeat();

            assertThat(writer.currentPath().getFileName()).hasToString("20260729.0.ascham");
            assertThat(dir.list()).extracting(s -> s.path().getFileName().toString())
                    .containsExactly("20260728.0.ascham", "20260729.0.ascham");

            // And the rotated-away segment is fully sealed, so the roller can archive it at once.
            try (SnapshotReader reader = SnapshotReader.open(yesterday)) {
                assertThat(reader.snapshot().batches().get(0).sealed()).isTrue();
                assertThat(reader.snapshot().batches().get(0).tsMin()).isEqualTo(1000);
            }
        }
    }

    @Test
    void heartbeatRotatesAnIdleWriterAtTheSubDayIntervalBoundary() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T07:59:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, RotateFixtures.tsStats(64), 8, 1L,
                RollCycle.parse("4h"), clock, RotateFixtures.counterNanoClock())) {
            RotateFixtures.append(writer, 1000, 1);
            assertThat(writer.currentPath().getFileName()).hasToString("20260728.0400.240m.0.ascham");

            writer.heartbeat();
            assertThat(dir.list()).hasSize(1); // same interval: no rotation

            clock.set(Instant.parse("2026-07-28T08:00:30Z")); // interval turns; producer is idle
            writer.heartbeat();

            assertThat(writer.currentPath().getFileName()).hasToString("20260728.0800.240m.0.ascham");
            assertThat(dir.list()).extracting(s -> s.path().getFileName().toString())
                    .containsExactly("20260728.0400.240m.0.ascham", "20260728.0800.240m.0.ascham");
        }
    }

    @Test
    void heartbeatStillAdvancesLivenessWhenNoRotationIsDue() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                RollCycle.DAILY, clock, RotateFixtures.counterNanoClock())) {
            RotateFixtures.append(writer, 1000, 1);
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
