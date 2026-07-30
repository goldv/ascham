package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.rotate.SegmentDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R5 against the real catalog: the full lifecycle of a day — rolled, held through grace, then
 * released — with the grace clock coming from the store rather than the roller.
 *
 * <p>Needs the dev stack and the built extension; see {@link RollIT}.
 */
@Tag("catalog")
class ReclaimIT {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 28);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 29);

    @TempDir
    Path arenaBase;

    private ColdConfig config;
    private DuckDbRollExecutor executor;

    @BeforeEach
    void setUp() {
        Path extension = Path.of(System.getProperty("io.ito.cold.arenaExtension",
                "arena-duckdb/build/arena.duckdb_extension"));
        String ns = "rc_" + Long.toHexString(System.nanoTime());
        config = ColdConfig.builder()
                .arenaBaseDir(arenaBase)
                .arenaExtension(extension)
                .catalog("http://localhost:8181/catalog", "ito")
                .namespace(ns)
                .metaNamespace(ns + "_meta")
                .sortColumns(java.util.Map.of("quotes", List.of("sym", "ts")))
                .livenessProbe(Duration.ofMillis(200))
                .build();
        executor = new DuckDbRollExecutor(config);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void aRolledDayIsHeldThroughGraceThenReleased() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, D2, TODAY), 200);
        SegmentDirectory dir = new SegmentDirectory(arenaBase, "quotes");

        new TableRoller(config, executor).roll("quotes", TODAY);
        assertThat(dir.list()).hasSize(3);

        // A generous grace window: the days were archived seconds ago, so nothing may go yet.
        SegmentReclaimer.Result held = new SegmentReclaimer(config, executor, Duration.ofHours(1))
                .reclaim("quotes");
        assertThat(held.unlinked()).isEmpty();
        assertThat(dir.list()).hasSize(3);

        // With grace effectively zero, the two archived days are released — and only those.
        SegmentReclaimer.Result released = new SegmentReclaimer(config, executor, Duration.ZERO)
                .reclaim("quotes");
        assertThat(released.unlinked()).hasSize(2);
        assertThat(released.bytesFreed()).isPositive();
        assertThat(dir.list()).extracting(s -> s.path().getFileName().toString())
                .containsExactly("20260729.0.arena"); // today's segment survives

        // The archived rows are still queryable — they moved, they were not lost.
        assertThat(historicalRows()).isEqualTo(400);
    }

    @Test
    void theGraceWindowIsMeasuredByTheStoreNotTheRoller() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, TODAY), 100);
        new TableRoller(config, executor).roll("quotes", TODAY);

        // The catalog stamped committed_at moments ago, so a one-hour window must exclude it no
        // matter what this machine's clock says — the comparison happens in the store.
        assertThat(executor.reclaimable("quotes", Duration.ofHours(1))).isEmpty();
        assertThat(executor.reclaimable("quotes", Duration.ZERO))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.day()).isEqualTo(D1);
                    assertThat(day.segmentNames()).containsExactly("20260727.0.arena");
                });
    }

    @Test
    void afterReclamationTheDayIsNotRolledAgain() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, TODAY), 150);
        TableRoller roller = new TableRoller(config, executor);
        roller.roll("quotes", TODAY);
        new SegmentReclaimer(config, executor, Duration.ZERO).reclaim("quotes");

        // The segments are gone, so the day is no longer discoverable — and the log still records it,
        // so even if it reappeared it would be recognised as done rather than duplicated.
        TableRoller.RollResult again = roller.roll("quotes", TODAY);
        assertThat(again.rolled()).isEmpty();
        assertThat(historicalRows()).isEqualTo(150);
        assertThat(executor.highestRolledDay("quotes")).contains(D1);
    }

    @Test
    void aFullServicePassRollsAndThenReclaims() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, D2, TODAY), 120);
        // Grace zero: one pass does the whole lifecycle, which is what a backlog drain looks like.
        RollService service = new RollService(config, executor, Duration.ZERO, 0, clockAt(TODAY));

        RollService.Pass pass = service.runOnce();

        assertThat(pass.failures()).isEmpty();
        assertThat(pass.rowsRolled()).isEqualTo(240);
        assertThat(pass.segmentsReclaimed()).isEqualTo(2);
        assertThat(new SegmentDirectory(arenaBase, "quotes").list()).hasSize(1);
        assertThat(historicalRows()).isEqualTo(240);
        assertThat(pass.arenaBytes()).isPositive();
    }

    @Test
    void multipleTablesRollAndReclaimIndependently() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, TODAY), 80);
        copyTable("trades");

        RollService service = new RollService(config, executor, Duration.ZERO, 0, clockAt(TODAY));
        assertThat(service.discoverTables()).containsExactly("quotes", "trades");

        RollService.Pass pass = service.runOnce();

        assertThat(pass.failures()).isEmpty();
        assertThat(pass.rowsRolled()).isEqualTo(160); // 80 per table
        assertThat(pass.segmentsReclaimed()).isEqualTo(2);
        assertThat(rowsIn("quotes")).isEqualTo(80);
        assertThat(rowsIn("trades")).isEqualTo(80);
        // Each table keeps its own watermark.
        assertThat(executor.highestRolledDay("quotes")).contains(D1);
        assertThat(executor.highestRolledDay("trades")).contains(D1);
    }

    /** A clock fixed at the given UTC date, so "today" in a pass is the test's today. */
    private static java.time.Clock clockAt(LocalDate day) {
        return java.time.Clock.fixed(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                java.time.ZoneOffset.UTC);
    }

    private long historicalRows() {
        return rowsIn("quotes");
    }

    private long rowsIn(String table) {
        try (Statement st = executor.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + config.qualified(table))) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) {
            throw new AssertionError("count failed for " + table, e);
        }
    }

    private void copyTable(String table) {
        Path from = arenaBase.resolve("quotes");
        Path to = arenaBase.resolve(table);
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
}
