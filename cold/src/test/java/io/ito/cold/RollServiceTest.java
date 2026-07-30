package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.rotate.SegmentDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Backlog draining, multi-table isolation, and the ascending-abort invariant (I1). */
class RollServiceTest {

    @TempDir
    Path base;

    private static final LocalDate D1 = LocalDate.of(2026, 7, 25);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 26);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D4 = LocalDate.of(2026, 7, 28);
    private static final Duration GRACE = Duration.ofMinutes(15);

    @Test
    void aMultiDayBacklogDrainsOldestFirstInOneRun() {
        // The writer was down for three days; all of them are complete and pending.
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 20);
        FakeRollExecutor executor = new FakeRollExecutor();

        TableRoller.RollResult result = new TableRoller(config(), executor).roll("quotes", D4);

        assertThat(result.days()).extracting(TableRoller.DayResult::day).containsExactly(D1, D2, D3);
        // Strictly ascending: the watermark can only stand in for the whole set if no day is skipped.
        assertThat(executor.rollDayCalls())
                .containsExactly("rollDay:quotes:" + D1, "rollDay:quotes:" + D2, "rollDay:quotes:" + D3);
    }

    @Test
    void afailedDayStopsEveryLaterDay() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 20);
        FakeRollExecutor executor = new FakeRollExecutor();
        executor.failOnDay = D2; // e.g. the catalog rejected this commit

        assertThatThrownBy(() -> new TableRoller(config(), executor).roll("quotes", D4))
                .isInstanceOf(ColdException.class);

        // D1 rolled, D2 failed, and D3 was never attempted — rolling it would leave a permanent hole
        // at D2 that neither the historical nor the realtime side would serve (§3.1, hole 1).
        assertThat(executor.rollDayCalls())
                .containsExactly("rollDay:quotes:" + D1, "rollDay:quotes:" + D2);
        assertThat(executor.highestRolledDay("quotes")).contains(D1);
    }

    @Test
    void everyTableIsDiscoveredAndRolledIndependently() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        writeSecondTable("trades");
        FakeRollExecutor executor = new FakeRollExecutor();

        RollService service = new RollService(config(), executor, GRACE, 0, clockAt(D4));
        assertThat(service.discoverTables()).containsExactly("quotes", "trades");

        RollService.Pass pass = service.runOnce();
        assertThat(pass.tables()).extracting(RollService.TableOutcome::table)
                .containsExactly("quotes", "trades");
        assertThat(pass.failures()).isEmpty();
    }

    @Test
    void oneBadTableDoesNotBlockTheOthers() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        writeSecondTable("trades");
        FakeRollExecutor executor = new FakeRollExecutor();
        executor.failOnDay = D1; // only quotes fails; trades has the same days but must still drain
        executor.failOnTable = "quotes";

        RollService.Pass pass = new RollService(config(), executor, GRACE, 0, clockAt(D4)).runOnce();

        assertThat(pass.failures()).extracting(RollService.TableOutcome::table).containsExactly("quotes");
        // trades still rolled: containing the failure keeps one bad table from stalling the tier.
        RollService.TableOutcome trades = pass.tables().stream()
                .filter(t -> t.table().equals("trades")).findFirst().orElseThrow();
        assertThat(trades.failed()).isFalse();
        assertThat(trades.roll().rolled()).isNotEmpty();
    }

    @Test
    void aPassRollsThenReclaimsWithinTheSameRun() {
        ColdFixtures.writeDays(base, List.of(D1, D2, D3), 10);
        FakeRollExecutor executor = new FakeRollExecutor();
        RollService service = new RollService(config(), executor, GRACE, 0, clockAt(D4));

        RollService.Pass first = service.runOnce();
        assertThat(first.rowsRolled()).isPositive();
        // Nothing is reclaimed yet — the days were archived moments ago.
        assertThat(first.segmentsReclaimed()).isZero();

        executor.nowMillis += GRACE.toMillis();
        RollService.Pass second = service.runOnce();

        assertThat(second.rowsRolled()).isZero();          // nothing new to roll
        assertThat(second.segmentsReclaimed()).isEqualTo(2); // D1 and D2 released
        assertThat(new SegmentDirectory(base, "quotes").list()).hasSize(1);
    }

    @Test
    void arenaBytesAreReportedForPressureMonitoring() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 10);
        RollService service = new RollService(config(), new FakeRollExecutor(), GRACE, 0, clockAt(D4));
        assertThat(service.arenaBytes()).isPositive();
    }

    @Test
    void anEmptyArenaHasNoTables() {
        RollService service = new RollService(config(), new FakeRollExecutor(), GRACE, 0, clockAt(D4));
        assertThat(service.discoverTables()).isEmpty();
        assertThat(service.runOnce().tables()).isEmpty();
    }

    @Test
    void directoriesWithoutSegmentsAreNotTables() {
        ColdFixtures.writeDays(base, List.of(D1), 5);
        try {
            Files.createDirectories(base.resolve("not_a_table"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        RollService service = new RollService(config(), new FakeRollExecutor(), GRACE, 0, clockAt(D4));
        assertThat(service.discoverTables()).containsExactly("quotes");
    }

    /** A second table with its own segments, so multi-table behaviour is real rather than mocked. */
    private void writeSecondTable(String table) {
        Path from = base.resolve("quotes");
        Path to = base.resolve(table);
        try {
            Files.createDirectories(to);
            try (var files = Files.list(from)) {
                for (Path p : files.toList()) {
                    Files.copy(p, to.resolve(p.getFileName()));
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static java.time.Clock clockAt(LocalDate day) {
        return java.time.Clock.fixed(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                java.time.ZoneOffset.UTC);
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
