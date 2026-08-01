package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R2: writer-side segment reclamation is off unless explicitly asked for.
 *
 * <p>With a cold tier, the roll service owns unlinking — it is the only component that knows what
 * has been archived. Count-based eviction inside the writer knows nothing about that, so leaving it
 * on by default would silently destroy data that was never written down
 * (docs/cold-tier-design-plan.md §8.1).
 */
class RetentionPolicyTest {

    @TempDir
    Path base;

    private static final Instant T0 = Instant.parse("2026-07-28T10:00:00Z");

    @Test
    void byDefaultTheWriterNeverUnlinksItsOwnSegments() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(T0);

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            RotateFixtures.append(writer, 1000, 1);
            for (int i = 0; i < 5; i++) {
                writer.rotate();
                RotateFixtures.append(writer, 1000 + i, i);
            }
        }

        // All six segments survive: nothing is reclaimed without an explicit backstop.
        assertThat(dir.list()).hasSize(6);
    }

    @Test
    void anExplicitBackstopStillEvictsOldestFirst() {
        SegmentDirectory dir = new SegmentDirectory(base, "trades");
        ArenaSchema schema = RotateFixtures.tsStats(64);
        RotateFixtures.MutableClock clock = new RotateFixtures.MutableClock(T0);

        try (RotatingWriter writer = RotatingWriter.open(dir, schema, 8, 1L,
                Retention.emergencyBackstop(2),
                new DailyRotationPolicy(), clock, RotateFixtures.counterNanoClock())) {
            RotateFixtures.append(writer, 1000, 1);
            writer.rotate();
            writer.rotate();
            writer.rotate();

            assertThat(dir.list()).hasSize(2);
            // Oldest-first eviction: the surviving pair is the newest two sequence numbers.
            assertThat(dir.list()).extracting(s -> s.path().getFileName().toString())
                    .containsExactly("20260728.2.arena", "20260728.3.arena");
        }
    }

    @Test
    void backstopRejectsANonsensicalLimit() {
        assertThatThrownBy(() -> Retention.emergencyBackstop(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Retention.none()");
        assertThatThrownBy(() -> Retention.emergencyBackstop(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneAndBackstopReportTheirState() {
        assertThat(Retention.none().enabled()).isFalse();
        assertThat(Retention.none()).hasToString("Retention.none()");
        assertThat(Retention.emergencyBackstop(3).enabled()).isTrue();
        assertThat(Retention.emergencyBackstop(3).maxSegments()).isEqualTo(3);
        assertThat(Retention.emergencyBackstop(3)).hasToString("Retention.emergencyBackstop(3)");
    }
}
