package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionUnlinkTest {

    @TempDir
    Path base;

    @Test
    void evictedSegmentStaysReadableForAlreadyMappedReaders() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(Instant.parse("2026-07-28T10:00:00Z"));

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L, 2, // retention = 2
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            writer.append(RotateFixtures.row(1000, 1));
            Path oldest = writer.currentPath();

            // A reader maps the oldest segment before it is evicted.
            try (SnapshotReader mappedBeforeEviction = SnapshotReader.open(oldest)) {
                assertThat(rows(mappedBeforeEviction)).isEqualTo(1);

                writer.rotate(); // -> seq 1 (retention keeps [0, 1])
                writer.rotate(); // -> seq 2 (evicts seq 0)

                // The file is unlinked...
                assertThat(Files.exists(oldest)).isFalse();
                // ...but the reader that mapped it before eviction still reads it (kernel refcount).
                assertThat(rows(mappedBeforeEviction)).isEqualTo(1);
                // ...and a fresh open of the unlinked path fails.
                assertThatThrownBy(() -> SnapshotReader.open(oldest)).isInstanceOf(RuntimeException.class);
            }
        }
    }

    private static int rows(SnapshotReader reader) {
        return reader.snapshot().batches().stream().mapToInt(v -> v.rowCount()).sum();
    }
}
