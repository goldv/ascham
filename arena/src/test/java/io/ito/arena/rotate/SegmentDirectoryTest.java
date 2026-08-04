package io.ito.arena.rotate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentDirectoryTest {

    @TempDir
    Path base;

    @Test
    void parsesLegacyDailyNamesAsWholeDayIntervals() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        touch("20260804.3.arena");

        List<SegmentDirectory.SegmentName> names = dir.list();
        assertThat(names).hasSize(1);
        SegmentDirectory.SegmentName s = names.get(0);
        assertThat(s.start()).isEqualTo(Instant.parse("2026-08-04T00:00:00Z"));
        assertThat(s.end()).isEqualTo(Instant.parse("2026-08-05T00:00:00Z"));
        assertThat(s.cycle()).isEqualTo(RollCycle.DAILY);
        assertThat(s.seq()).isEqualTo(3);
        assertThat(s.day()).isEqualTo(LocalDate.of(2026, 8, 4));
    }

    @Test
    void parsesSubDayNamesWithStartAndDuration() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        touch("20260804.0400.240m.7.arena");

        SegmentDirectory.SegmentName s = dir.list().get(0);
        assertThat(s.start()).isEqualTo(Instant.parse("2026-08-04T04:00:00Z"));
        assertThat(s.end()).isEqualTo(Instant.parse("2026-08-04T08:00:00Z"));
        assertThat(s.cycle()).isEqualTo(RollCycle.parse("4h"));
        assertThat(s.seq()).isEqualTo(7);
    }

    @Test
    void ignoresFilesThatAreNotSegments() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        touch("20260804.0.arena.tmp");
        touch("notes.txt");
        touch("20260804.arena");
        assertThat(dir.list()).isEmpty();
    }

    @Test
    void mixedLegacyAndSubDayNamesSortByIntervalStartThenCycleThenSeq() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        touch("20260804.0400.240m.1.arena");
        touch("20260804.0.arena");                // whole day: starts at midnight
        touch("20260804.0000.240m.0.arena");
        touch("20260804.0400.240m.0.arena");
        touch("20260803.2000.240m.0.arena");

        assertThat(dir.list()).extracting(s -> s.path().getFileName().toString()).containsExactly(
                "20260803.2000.240m.0.arena",
                "20260804.0000.240m.0.arena", // same start as the daily name but a shorter cycle
                "20260804.0.arena",
                "20260804.0400.240m.0.arena",
                "20260804.0400.240m.1.arena");
    }

    @Test
    void nextSeqIsScopedToTheIntervalAndCycle() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        touch("20260804.0400.240m.0.arena");
        touch("20260804.0400.240m.1.arena");
        touch("20260804.0800.240m.5.arena");

        RollCycle fourHours = RollCycle.parse("4h");
        assertThat(dir.nextSeq(Instant.parse("2026-08-04T04:00:00Z"), fourHours)).isEqualTo(2);
        assertThat(dir.nextSeq(Instant.parse("2026-08-04T08:00:00Z"), fourHours)).isEqualTo(6);
        assertThat(dir.nextSeq(Instant.parse("2026-08-04T12:00:00Z"), fourHours)).isEqualTo(0);
        // A different cycle over the same start is a different interval.
        assertThat(dir.nextSeq(Instant.parse("2026-08-04T04:00:00Z"), RollCycle.parse("6h"))).isEqualTo(0);
    }

    @Test
    void resumingALegacyDirectoryContinuesItsDailySequence() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        touch("20260804.0.arena");
        touch("20260804.1.arena");

        Instant midnight = Instant.parse("2026-08-04T00:00:00Z");
        assertThat(dir.nextSeq(midnight, RollCycle.DAILY)).isEqualTo(2);
        assertThat(dir.segmentPath(midnight, RollCycle.DAILY, 2).getFileName())
                .hasToString("20260804.2.arena");
    }

    @Test
    void segmentPathRoundTripsThroughList() throws IOException {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        Instant start = Instant.parse("2026-08-04T18:00:00Z");
        RollCycle sixHours = RollCycle.parse("6h");
        Path p = dir.segmentPath(start, sixHours, 4);
        assertThat(p.getFileName()).hasToString("20260804.1800.360m.4.arena");

        Files.createFile(p);
        SegmentDirectory.SegmentName parsed = dir.list().get(0);
        assertThat(parsed.start()).isEqualTo(start);
        assertThat(parsed.cycle()).isEqualTo(sixHours);
        assertThat(parsed.seq()).isEqualTo(4);
    }

    private void touch(String name) throws IOException {
        Files.createFile(base.resolve("quotes").resolve(name));
    }
}
