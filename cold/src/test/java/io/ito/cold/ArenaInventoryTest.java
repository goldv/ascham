package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.rotate.DailyRotationPolicy;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Discovery, the freeze check, and I2 day-alignment verification (cold-tier plan §4 steps 1, 2, 4). */
class ArenaInventoryTest {

    @TempDir
    Path base;

    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 28);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 29);

    @Test
    void pendingDaysAreOldestFirstAndExcludeToday() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 10);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");

        // "Today" is D3, so only the two completed days are candidates.
        List<ArenaInventory.DaySegments> pending = ArenaInventory.pendingDays(dir, D3);
        assertThat(pending).extracting(ArenaInventory.DaySegments::day).containsExactly(D1, D2);

        // A later "today" makes every day a candidate.
        assertThat(ArenaInventory.pendingDays(dir, D3.plusDays(1)))
                .extracting(ArenaInventory.DaySegments::day).containsExactly(D1, D2, D3);
    }

    @Test
    void aDaysSegmentsAreGroupedAndOrdered() {
        ColdFixtures.writeDays(base, List.of(D1), 5);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        // Force a second segment on the same day, as a capacity rotation would.
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 64, 2L,
                new DailyRotationPolicy(),
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant()),
                ColdFixtures.counterNanoClock())) {
            ColdFixtures.append(writer,
                    D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L, "AAPL", 1);
        }

        List<ArenaInventory.DaySegments> pending = ArenaInventory.pendingDays(dir, D2);
        assertThat(pending).singleElement().satisfies(day -> {
            assertThat(day.day()).isEqualTo(D1);
            assertThat(day.segments()).hasSizeGreaterThan(1);
            assertThat(day.fileNames()).isSorted(); // oldest sequence first
        });
    }

    @Test
    void aDayIsFrozenOnceANewerSegmentExists() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        List<ArenaInventory.DaySegments> pending = ArenaInventory.pendingDays(dir, D3);

        // D1 has D2's segment after it, so no writer can touch it again — decided by ordering alone,
        // with no waiting on a heartbeat probe.
        assertThat(ArenaInventory.isFrozen(dir, pending.get(0), Duration.ofDays(1))).isTrue();
    }

    @Test
    void theNewestDayFallsBackToTheHeartbeatProbe() {
        ColdFixtures.writeDays(base, List.of(D1), 10);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ArenaInventory.DaySegments onlyDay = ArenaInventory.pendingDays(dir, D2).get(0);

        // The writer closed, so its heartbeat is frozen: a short probe concludes it is gone.
        assertThat(ArenaInventory.isFrozen(dir, onlyDay, Duration.ofMillis(50))).isTrue();
    }

    @Test
    void aLiveWriterOnTheNewestSegmentIsNotFrozen() throws Exception {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(),
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant()),
                ColdFixtures.counterNanoClock())) {
            ColdFixtures.append(writer,
                    D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L, "AAPL", 1);

            ArenaInventory.DaySegments day = ArenaInventory.pendingDays(dir, D2).get(0);
            // Heartbeat on another thread while the probe watches: the writer is demonstrably alive,
            // so the day must not be archived even though its file day is in the past.
            Thread beating = new Thread(() -> {
                for (int i = 0; i < 40; i++) {
                    writer.heartbeat();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            beating.start();
            try {
                assertThat(ArenaInventory.isFrozen(dir, day, Duration.ofMillis(100))).isFalse();
            } finally {
                beating.interrupt();
                beating.join();
            }
        }
    }

    @Test
    void dayAlignmentPassesForAWellBehavedWriter() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 20);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        for (ArenaInventory.DaySegments day : ArenaInventory.pendingDays(dir, D3)) {
            ArenaInventory.verifyDayAlignment(day); // must not throw
        }
    }

    @Test
    void dayAlignmentRejectsRowsFromTheWrongDay() {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        long d1Start = D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(),
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant()),
                ColdFixtures.counterNanoClock())) {
            ColdFixtures.append(writer, d1Start + 1_000L, "AAPL", 1);
            // A straggler whose event time lands on the next day, while the file day stays D1. This
            // is exactly the skew that would desynchronise the partition from the watermark (§3.1).
            ColdFixtures.append(writer,
                    D2.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L + 5, "MSFT", 2);
        }

        ArenaInventory.DaySegments day = ArenaInventory.pendingDays(dir, D3).get(0);
        assertThatThrownBy(() -> ArenaInventory.verifyDayAlignment(day))
                .isInstanceOf(ArenaInventory.DayAlignmentException.class)
                .hasMessageContaining("escapes day 2026-07-27");
    }

    @Test
    void dayAlignmentRejectsAnUnsealedBatch() throws Exception {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        long d1Start = D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
        RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(),
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant()),
                ColdFixtures.counterNanoClock());
        ColdFixtures.append(writer, d1Start + 1_000L, "AAPL", 1);
        // Deliberately NOT closed: the batch stays in progress with unpublished stats, which is what
        // a crashed writer leaves behind. Verification must refuse to trust it.
        try {
            ArenaInventory.DaySegments day = ArenaInventory.pendingDays(dir, D3).get(0);
            assertThatThrownBy(() -> ArenaInventory.verifyDayAlignment(day))
                    .isInstanceOf(ArenaInventory.DayAlignmentException.class)
                    .hasMessageContaining("still in progress");
        } finally {
            writer.close();
        }
    }

    @Test
    void anEmptyTableDirectoryHasNothingPending() {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        assertThat(ArenaInventory.pendingDays(dir, D3)).isEmpty();
    }
}
