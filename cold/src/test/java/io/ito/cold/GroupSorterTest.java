package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.rotate.RollCycle;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.TimeStampNanoTZVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The index sort over zero-copy segment roots: SegmentGroup addressing + GroupSorter ordering. */
class GroupSorterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 30);

    @TempDir
    Path base;

    @Test
    void groupSpansSegmentsAndResolvesGlobalRows() {
        List<Path> segments = writeShuffledDay(300, true);
        assertThat(segments).hasSizeGreaterThan(1); // the forced rotation split the day

        try (SegmentGroup group = SegmentGroup.open(segments)) {
            assertThat(group.rowCount()).isEqualTo(300);
            // Every global row resolves to the batch whose window contains it.
            for (int g = 0; g < group.rowCount(); g++) {
                int b = group.batchOf(g);
                assertThat(g).isGreaterThanOrEqualTo(group.batchStart(b));
                assertThat(g - group.batchStart(b)).isLessThan(group.root(b).getRowCount());
            }
        }
    }

    @Test
    void sortsBySymbolThenTimeAcrossSegmentAndBatchBoundaries() {
        List<Path> segments = writeShuffledDay(300, true);
        try (SegmentGroup group = SegmentGroup.open(segments)) {
            int[] index = GroupSorter.sortedIndex(group, List.of("sym", "ts"));

            assertThat(index).hasSize(300);
            assertThat(java.util.Arrays.stream(index).sorted().toArray())
                    .isEqualTo(java.util.stream.IntStream.range(0, 300).toArray()); // a permutation

            String prevSym = null;
            long prevTs = Long.MIN_VALUE;
            for (int g : index) {
                int b = group.batchOf(g);
                int row = g - group.batchStart(b);
                String sym = new String(((VarCharVector) group.root(b).getVector(1)).get(row),
                        StandardCharsets.UTF_8);
                long ts = ((TimeStampNanoTZVector) group.root(b).getVector(0)).get(row);
                if (prevSym != null) {
                    assertThat(sym).isGreaterThanOrEqualTo(prevSym);
                    if (sym.equals(prevSym)) {
                        assertThat(ts).isGreaterThanOrEqualTo(prevTs);
                    }
                }
                prevSym = sym;
                prevTs = ts;
            }
        }
    }

    @Test
    void timeOnlySortRecoversAppendOrderOfAShuffledDay() {
        List<Path> segments = writeShuffledDay(200, false);
        try (SegmentGroup group = SegmentGroup.open(segments)) {
            int[] index = GroupSorter.sortedIndex(group, List.of("ts"));
            long prev = Long.MIN_VALUE;
            for (int g : index) {
                int b = group.batchOf(g);
                long ts = ((TimeStampNanoTZVector) group.root(b).getVector(0))
                        .get(g - group.batchStart(b));
                assertThat(ts).isGreaterThanOrEqualTo(prev);
                prev = ts;
            }
        }
    }

    @Test
    void unknownSortColumnIsRejected() {
        List<Path> segments = writeShuffledDay(10, false);
        try (SegmentGroup group = SegmentGroup.open(segments)) {
            assertThatThrownBy(() -> GroupSorter.sortedIndex(group, List.of("nope")))
                    .isInstanceOf(ColdException.class)
                    .hasMessageContaining("nope");
        }
    }

    @Test
    void unsealedBatchesAreAHardError() {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock =
                new ColdFixtures.MutableClock(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, ColdFixtures.counterNanoClock())) {
            ColdFixtures.append(writer, dayNanos(0), "AAPL", 1L);
            List<Path> segments = dir.list().stream().map(SegmentDirectory.SegmentName::path).toList();
            // The writer is still open: the row's batch is unsealed, so the group must refuse.
            assertThatThrownBy(() -> SegmentGroup.open(segments))
                    .isInstanceOf(ColdException.class)
                    .hasMessageContaining("not sealed");
        }
    }

    /**
     * One UTC day of rows with symbols round-robined (so append order is NOT symbol order), split
     * across two segments via a forced mid-day rotation when {@code split}. Timestamps ascend with
     * a sub-microsecond jitter component.
     */
    private List<Path> writeShuffledDay(int rows, boolean split) {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock =
                new ColdFixtures.MutableClock(DAY.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, ColdFixtures.counterNanoClock())) {
            for (int r = 0; r < rows; r++) {
                if (split && r == rows / 2) {
                    writer.rotate(); // capacity-style rotation: same day, next sequence
                }
                ColdFixtures.append(writer, dayNanos(r),
                        ColdFixtures.SYMBOLS[r % ColdFixtures.SYMBOLS.length], 1_000L + r);
            }
        }
        // Move the clock past the day so nothing considers these segments live.
        clock.set(DAY.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        List<Path> segments = new ArrayList<>();
        dir.list().forEach(s -> segments.add(s.path()));
        return segments;
    }

    private static long dayNanos(int row) {
        Instant start = DAY.atStartOfDay(ZoneOffset.UTC).toInstant();
        return start.getEpochSecond() * 1_000_000_000L + row * 1_000_000L + (row % 997);
    }
}
