package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.read.SnapshotReader;
import io.ito.arena.rotate.SegmentDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Grace-gated reclamation — invariant I3 (docs/cold-tier-design-plan.md §3.2). */
class SegmentReclaimerTest {

    @TempDir
    Path base;

    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 28);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 29);
    private static final Duration GRACE = Duration.ofMinutes(15);

    @Test
    void nothingIsReclaimedBeforeTheGracePeriodElapses() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 10);
        FakeRollExecutor executor = new FakeRollExecutor();
        TableRoller roller = new TableRoller(config(), executor);
        roller.roll("quotes", D3);

        // Rolled, but only a minute ago: a query whose cutover cache has not refreshed may still be
        // serving these rows from the arena, so they must stay.
        executor.nowMillis += Duration.ofMinutes(1).toMillis();
        SegmentReclaimer.Result result = new SegmentReclaimer(config(), executor, GRACE).reclaim("quotes");

        assertThat(result.unlinked()).isEmpty();
        assertThat(new SegmentDirectory(base, "quotes").list()).hasSize(3);
    }

    @Test
    void archivedSegmentsAreReleasedOnceGraceHasPassed() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 10);
        FakeRollExecutor executor = new FakeRollExecutor();
        new TableRoller(config(), executor).roll("quotes", D3);

        executor.nowMillis += GRACE.toMillis();
        SegmentReclaimer.Result result = new SegmentReclaimer(config(), executor, GRACE).reclaim("quotes");

        // D1 and D2 were rolled and are now released; D3 was never rolled, so it stays.
        assertThat(result.unlinked()).hasSize(2);
        assertThat(result.bytesFreed()).isPositive();
        assertThat(new SegmentDirectory(base, "quotes").list())
                .extracting(s -> s.path().getFileName().toString())
                .containsExactly("20260729.0.arena");
    }

    @Test
    void onlySegmentsNamedByTheRollLogAreTouched() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 10);
        FakeRollExecutor executor = new FakeRollExecutor();
        // Only D1 is archived — D2's segment must survive even though it is older than D3.
        executor.logDayOnly("quotes", D1, 10, List.of("20260727.0.arena"));
        executor.nowMillis += GRACE.toMillis();

        new SegmentReclaimer(config(), executor, GRACE).reclaim("quotes");

        assertThat(new SegmentDirectory(base, "quotes").list())
                .extracting(s -> s.path().getFileName().toString())
                .containsExactly("20260728.0.arena", "20260729.0.arena");
    }

    @Test
    void theNewestSegmentIsNeverReclaimedEvenIfTheLogNamesIt() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        FakeRollExecutor executor = new FakeRollExecutor();
        // A corrupted or hand-edited log claiming the segment a writer would append to.
        executor.logDayOnly("quotes", D2, 10, List.of("20260728.0.arena"));
        executor.nowMillis += GRACE.toMillis();

        SegmentReclaimer.Result result = new SegmentReclaimer(config(), executor, GRACE).reclaim("quotes");

        assertThat(result.unlinked()).isEmpty();
        assertThat(Files.exists(base.resolve("quotes/20260728.0.arena"))).isTrue();
    }

    @Test
    void aLogEntryPointingOutsideTheTableDirectoryIsRefused() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        Path outsider = base.resolve("secret.arena");
        try {
            Files.writeString(outsider, "not a segment");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        FakeRollExecutor executor = new FakeRollExecutor();
        executor.logDayOnly("quotes", D1, 10, List.of("../secret.arena"));
        executor.nowMillis += GRACE.toMillis();

        new SegmentReclaimer(config(), executor, GRACE).reclaim("quotes");

        assertThat(Files.exists(outsider)).isTrue(); // path traversal refused
    }

    @Test
    void reclaimingTwiceIsHarmless() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 10);
        FakeRollExecutor executor = new FakeRollExecutor();
        new TableRoller(config(), executor).roll("quotes", D3);
        executor.nowMillis += GRACE.toMillis();

        SegmentReclaimer reclaimer = new SegmentReclaimer(config(), executor, GRACE);
        assertThat(reclaimer.reclaim("quotes").unlinked()).hasSize(2);
        assertThat(reclaimer.reclaim("quotes").unlinked()).isEmpty(); // already gone
        assertThat(new SegmentDirectory(base, "quotes").list()).hasSize(1);
    }

    @Test
    void aReaderThatMappedBeforeReclamationKeepsReading() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        FakeRollExecutor executor = new FakeRollExecutor();
        new TableRoller(config(), executor).roll("quotes", D2);
        executor.nowMillis += GRACE.toMillis();

        Path victim = base.resolve("quotes/20260727.0.arena");
        try (SnapshotReader mappedFirst = SnapshotReader.open(victim)) {
            int rowsBefore = totalRows(mappedFirst);

            new SegmentReclaimer(config(), executor, GRACE).reclaim("quotes");

            assertThat(Files.exists(victim)).isFalse();
            // The kernel keeps the inode alive for existing mappings, so an in-flight query that
            // opened before the unlink is unaffected — reclamation only stops *new* readers.
            assertThat(totalRows(mappedFirst)).isEqualTo(rowsBefore);
        }
    }

    @Test
    void anUnknownTableReclaimsNothing() {
        SegmentReclaimer.Result result =
                new SegmentReclaimer(config(), new FakeRollExecutor(), GRACE).reclaim("nope");
        assertThat(result.isEmpty()).isTrue();
        assertThat(Files.exists(base.resolve("nope"))).isFalse();
    }

    private static int totalRows(SnapshotReader reader) {
        return reader.snapshot().batches().stream().mapToInt(b -> b.rowCount()).sum();
    }

    private ColdConfig config() {
        return ColdConfig.builder()
                .arenaBaseDir(base)
                .arenaExtension(Path.of("unused"))
                .catalog("http://localhost:8181/catalog", "ito")
                .livenessProbe(Duration.ofMillis(50))
                .build();
    }
}
