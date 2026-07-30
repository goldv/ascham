package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpochBumpOnRestartTest {

    @TempDir
    Path base;

    @Test
    void restartUsesAHigherEpochThatReadersCanDistinguish() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        // First writer instance, epoch 1.
        Path first;
        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(1000, 1));
            first = writer.currentPath();
        }
        assertThat(dir.latestEpoch()).isEqualTo(OptionalLong.of(1));

        // Restart: bump the epoch off the directory's latest.
        long restartEpoch = dir.latestEpoch().orElse(0) + 1;
        Path second;
        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, restartEpoch,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(2000, 2));
            second = writer.currentPath();
        }

        // Same day, so the sequence advanced rather than restarting.
        assertThat(first.getFileName()).hasToString("20260728.0.arena");
        assertThat(second.getFileName()).hasToString("20260728.1.arena");
        // Each segment carries its writer's epoch; a reader can tell the instances apart.
        assertThat(epoch(first)).isEqualTo(1);
        assertThat(epoch(second)).isEqualTo(2);
        assertThat(dir.latestEpoch()).isEqualTo(OptionalLong.of(2));
    }

    private static long epoch(Path segment) {
        try (SnapshotReader reader = SnapshotReader.open(segment)) {
            return reader.writerEpoch();
        }
    }
}
