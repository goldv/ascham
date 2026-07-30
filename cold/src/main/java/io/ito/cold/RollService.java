package io.ito.cold;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drives the cold tier across every arena table: roll what is complete, then release what is safely
 * archived (docs/cold-tier-design-plan.md §4).
 *
 * <p>Pull-based by design — it asks the arena "which days are finished?" rather than reacting to
 * rotation events, so a run started at any time converges to the same place. That is what makes the
 * schedule uninteresting: a missed run, a crashed run, and a run during a catalog outage all recover
 * by simply running again.
 *
 * <p>Tables are independent. One table failing (a misaligned day, say) does not stop the others —
 * its failure is recorded and the run continues, because blocking every table on one bad one would
 * turn a contained problem into a total stall. Within a table, days remain strictly ordered and
 * abort on first failure (invariant I1).
 */
public final class RollService {

    private static final System.Logger LOG = System.getLogger(RollService.class.getName());

    /** What one table did in a pass. */
    public record TableOutcome(String table, TableRoller.RollResult roll, SegmentReclaimer.Result reclaim,
                               Throwable failure) {
        public boolean failed() {
            return failure != null;
        }
    }

    /** What a whole pass did. */
    public record Pass(List<TableOutcome> tables, long arenaBytes) {
        public List<TableOutcome> failures() {
            return tables.stream().filter(TableOutcome::failed).toList();
        }

        public long rowsRolled() {
            return tables.stream().filter(t -> !t.failed()).mapToLong(t -> t.roll().totalRows()).sum();
        }

        public long segmentsReclaimed() {
            return tables.stream().filter(t -> !t.failed()).mapToLong(t -> t.reclaim().unlinked().size()).sum();
        }
    }

    private final ColdConfig config;
    private final RollExecutor executor;
    private final Duration unlinkGrace;
    private final long arenaBytesAlertThreshold;
    private final Clock clock;

    public RollService(ColdConfig config, RollExecutor executor, Duration unlinkGrace,
                       long arenaBytesAlertThreshold) {
        this(config, executor, unlinkGrace, arenaBytesAlertThreshold, Clock.systemUTC());
    }

    /**
     * @param clock supplies "today" — the boundary no day at or after may be rolled, since the
     *              writer still owns it. Injectable so a run can be pinned to a specific date.
     */
    public RollService(ColdConfig config, RollExecutor executor, Duration unlinkGrace,
                       long arenaBytesAlertThreshold, Clock clock) {
        this.config = config;
        this.executor = executor;
        this.unlinkGrace = unlinkGrace;
        this.arenaBytesAlertThreshold = arenaBytesAlertThreshold;
        this.clock = clock;
    }

    /**
     * One full pass: every table rolled, then reclaimed. Never throws for a table-level failure —
     * inspect {@link Pass#failures()}. Safe to call repeatedly; each run re-derives its work.
     */
    public Pass runOnce() {
        List<TableOutcome> outcomes = new ArrayList<>();
        for (String table : discoverTables()) {
            outcomes.add(runTable(table));
        }
        long bytes = arenaBytes();
        // The arena is finite, and un-archived segments are never deleted. If the catalog has been
        // unreachable for a while, this is the number that grows — and the only warning before
        // /dev/shm fills and the writer starts failing.
        if (arenaBytesAlertThreshold > 0 && bytes > arenaBytesAlertThreshold) {
            LOG.log(System.Logger.Level.ERROR,
                    "arena is holding {0} bytes, above the {1}-byte alert threshold: rolls are not "
                            + "keeping up (catalog unreachable? a day stuck un-frozen?) and shared "
                            + "memory will eventually fill", bytes, arenaBytesAlertThreshold);
        }
        return new Pass(List.copyOf(outcomes), bytes);
    }

    private TableOutcome runTable(String table) {
        try {
            TableRoller.RollResult roll = new TableRoller(config, executor)
                    .roll(table, LocalDate.now(clock.withZone(ZoneOffset.UTC)));
            SegmentReclaimer.Result reclaim = new SegmentReclaimer(config, executor, unlinkGrace).reclaim(table);
            if (!roll.rolled().isEmpty() || !reclaim.isEmpty()) {
                LOG.log(System.Logger.Level.INFO,
                        "table {0}: rolled {1} day(s) ({2} rows), reclaimed {3} segment(s) ({4} bytes)",
                        table, roll.rolled().size(), roll.totalRows(), reclaim.unlinked().size(),
                        reclaim.bytesFreed());
            }
            return new TableOutcome(table, roll, reclaim, null);
        } catch (RuntimeException e) {
            // Contained on purpose: a bad day in one table must not stop the others from draining.
            LOG.log(System.Logger.Level.ERROR, "table " + table + ": roll failed, will retry next run", e);
            return new TableOutcome(table, new TableRoller.RollResult(table, List.of()),
                    new SegmentReclaimer.Result(table, List.of(), 0), e);
        }
    }

    /** Table directories under the arena base — those holding at least one segment file. */
    public List<String> discoverTables() {
        Path base = config.arenaBaseDir();
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(base)) {
            return dirs.filter(Files::isDirectory)
                    .filter(RollService::holdsSegments)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list arena tables under " + base, e);
        }
    }

    private static boolean holdsSegments(Path tableDir) {
        try (Stream<Path> files = Files.list(tableDir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".arena"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Total bytes of segment files currently held in the arena. */
    public long arenaBytes() {
        Path base = config.arenaBaseDir();
        if (!Files.isDirectory(base)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".arena"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
