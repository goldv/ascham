package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.rotate.RollCycle;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Discovery, the freeze check, and I2 interval-alignment verification (cold-tier plan §4 steps 1, 2, 4). */
class ArenaInventoryTest {

    @TempDir
    Path base;

    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 28);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 29);

    @Test
    void pendingIntervalsAreOldestFirstAndExcludeTheLiveOne() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 10);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");

        // "Now" is the start of D3, so only the two completed days are candidates.
        List<ArenaInventory.IntervalSegments> pending = ArenaInventory.pendingIntervals(dir, at(D3));
        assertThat(pending).extracting(ArenaInventory.IntervalSegments::day).containsExactly(D1, D2);

        // A later "now" makes every day a candidate.
        assertThat(ArenaInventory.pendingIntervals(dir, at(D3.plusDays(1))))
                .extracting(ArenaInventory.IntervalSegments::day).containsExactly(D1, D2, D3);
    }

    @Test
    void aCompletedSubDayIntervalOfTodayIsACandidate() {
        Instant morning = Instant.parse("2026-07-27T00:00:00Z");
        ColdFixtures.writeIntervals(base, RollCycle.parse("4h"),
                List.of(morning, morning.plus(Duration.ofHours(4))), 10);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");

        // At 09:00 the [00:00,04:00) and [04:00,08:00) intervals are complete; nothing is live.
        List<ArenaInventory.IntervalSegments> pending =
                ArenaInventory.pendingIntervals(dir, Instant.parse("2026-07-27T09:00:00Z"));
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).start()).isEqualTo(morning);
        assertThat(pending.get(0).end()).isEqualTo(morning.plus(Duration.ofHours(4)));

        // At 05:00 only the first is complete.
        assertThat(ArenaInventory.pendingIntervals(dir, Instant.parse("2026-07-27T05:00:00Z")))
                .hasSize(1);
    }

    @Test
    void overlappingIntervalsFromACycleChangeMergeIntoOneUnit() throws Exception {
        // A 4h writer produced [04:00,08:00), then a restart at 07:00 with a 6h cycle produced
        // [06:00,12:00): the declared intervals overlap, so they must roll as one atomic unit.
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        java.nio.file.Files.createFile(dir.tableDir().resolve("20260727.0400.240m.0.arena"));
        java.nio.file.Files.createFile(dir.tableDir().resolve("20260727.0600.360m.0.arena"));

        List<ArenaInventory.IntervalSegments> pending =
                ArenaInventory.pendingIntervals(dir, Instant.parse("2026-07-27T12:00:00Z"));
        assertThat(pending).singleElement().satisfies(unit -> {
            assertThat(unit.start()).isEqualTo(Instant.parse("2026-07-27T04:00:00Z"));
            assertThat(unit.end()).isEqualTo(Instant.parse("2026-07-27T12:00:00Z"));
            assertThat(unit.segments()).hasSize(2);
        });

        // Before the merged unit's end, the whole unit is withheld — not just the newer segment.
        assertThat(ArenaInventory.pendingIntervals(dir, Instant.parse("2026-07-27T09:00:00Z")))
                .isEmpty();
    }

    @Test
    void anIntervalsSegmentsAreGroupedAndOrdered() {
        ColdFixtures.writeDays(base, List.of(D1), 5);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        // Force a second segment on the same day, as a capacity rotation would.
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 64, 2L,
                RollCycle.DAILY,
                new ColdFixtures.MutableClock(at(D1)),
                ColdFixtures.counterNanoClock())) {
            ColdFixtures.append(writer, at(D1).getEpochSecond() * 1_000_000_000L, "AAPL", 1);
        }

        List<ArenaInventory.IntervalSegments> pending = ArenaInventory.pendingIntervals(dir, at(D2));
        assertThat(pending).singleElement().satisfies(unit -> {
            assertThat(unit.day()).isEqualTo(D1);
            assertThat(unit.segments()).hasSizeGreaterThan(1);
            assertThat(unit.fileNames()).isSorted(); // oldest sequence first
        });
    }

    @Test
    void anIntervalIsFrozenOnceANewerSegmentExists() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        List<ArenaInventory.IntervalSegments> pending = ArenaInventory.pendingIntervals(dir, at(D3));

        // D1 has D2's segment after it, so no writer can touch it again — decided by ordering alone,
        // with no waiting on a heartbeat probe.
        assertThat(ArenaInventory.isFrozen(dir, pending.get(0), Duration.ofDays(1))).isTrue();
    }

    @Test
    void theNewestIntervalFallsBackToTheHeartbeatProbe() {
        ColdFixtures.writeDays(base, List.of(D1), 10);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ArenaInventory.IntervalSegments only = ArenaInventory.pendingIntervals(dir, at(D2)).get(0);

        // The writer closed, so its heartbeat is frozen: a short probe concludes it is gone.
        assertThat(ArenaInventory.isFrozen(dir, only, Duration.ofMillis(50))).isTrue();
    }

    @Test
    void aLiveWriterOnTheNewestSegmentIsNotFrozen() throws Exception {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY,
                new ColdFixtures.MutableClock(at(D1)),
                ColdFixtures.counterNanoClock())) {
            ColdFixtures.append(writer, at(D1).getEpochSecond() * 1_000_000_000L, "AAPL", 1);

            ArenaInventory.IntervalSegments unit = ArenaInventory.pendingIntervals(dir, at(D2)).get(0);
            // Heartbeat on another thread while the probe watches: the writer is demonstrably alive,
            // so the interval must not be archived even though its declared end is in the past.
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
                assertThat(ArenaInventory.isFrozen(dir, unit, Duration.ofMillis(100))).isFalse();
            } finally {
                beating.interrupt();
                beating.join();
            }
        }
    }

    @Test
    void alignmentPassesForAWellBehavedWriter() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 20);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        for (ArenaInventory.IntervalSegments unit : ArenaInventory.pendingIntervals(dir, at(D3))) {
            ArenaInventory.verifyIntervalAlignment(unit); // must not throw
        }
    }

    @Test
    void alignmentRejectsRowsOutsideTheDeclaredInterval() {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        long d1Start = at(D1).getEpochSecond() * 1_000_000_000L;
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY,
                new ColdFixtures.MutableClock(at(D1)),
                ColdFixtures.counterNanoClock())) {
            ColdFixtures.append(writer, d1Start + 1_000L, "AAPL", 1);
            // A straggler whose event time lands on the next day, while the file day stays D1. This
            // is exactly the skew that would desynchronise the partition from the watermark (§3.1).
            ColdFixtures.append(writer, at(D2).getEpochSecond() * 1_000_000_000L + 5, "MSFT", 2);
        }

        ArenaInventory.IntervalSegments unit = ArenaInventory.pendingIntervals(dir, at(D3)).get(0);
        assertThatThrownBy(() -> ArenaInventory.verifyIntervalAlignment(unit))
                .isInstanceOf(ArenaInventory.IntervalAlignmentException.class)
                .hasMessageContaining("escapes the declared interval");
    }

    @Test
    void alignmentRejectsAnUnsealedBatch() {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        long d1Start = at(D1).getEpochSecond() * 1_000_000_000L;
        RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY,
                new ColdFixtures.MutableClock(at(D1)),
                ColdFixtures.counterNanoClock());
        ColdFixtures.append(writer, d1Start + 1_000L, "AAPL", 1);
        // Deliberately NOT closed: the batch stays in progress with unpublished stats, which is what
        // a crashed writer leaves behind. Verification must refuse to trust it.
        try {
            ArenaInventory.IntervalSegments unit = ArenaInventory.pendingIntervals(dir, at(D3)).get(0);
            assertThatThrownBy(() -> ArenaInventory.verifyIntervalAlignment(unit))
                    .isInstanceOf(ArenaInventory.IntervalAlignmentException.class)
                    .hasMessageContaining("still in progress");
        } finally {
            writer.close();
        }
    }

    @Test
    void anEmptyTableDirectoryHasNothingPending() {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        assertThat(ArenaInventory.pendingIntervals(dir, at(D3))).isEmpty();
    }

    private static Instant at(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
