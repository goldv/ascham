package io.ito.cold;

import io.ito.arena.read.SnapshotReader;
import io.ito.arena.rotate.SegmentDirectory;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rolls one table's completed days out of the arena into the historical store, per the protocol in
 * docs/cold-tier-design-plan.md §4.
 *
 * <p>Three rules carry the correctness of the whole design:
 *
 * <ul>
 *   <li><b>I1 — ascending, gapless.</b> Days are rolled oldest first and the run stops at the first
 *       failure. Never rolling day D before every earlier day is done is what lets a single
 *       "highest rolled day" stand in for the whole set; roll them out of order and a failed
 *       middle day would be excluded by both the historical and the realtime side of a query.</li>
 *   <li><b>I2 — day-alignment verified, not assumed.</b> A day's rows must actually fall inside that
 *       UTC day; a violation aborts the table rather than silently splitting it.</li>
 *   <li><b>I3 — reclamation is separate.</b> This class never deletes a segment. Unlinking is gated
 *       on the archive being durable and on a grace period, and lands in R5.</li>
 * </ul>
 *
 * <p>Rolling is idempotent. If a run dies part-way, the next one re-derives what to do from the
 * store itself — there is no local state to lose or reconcile.
 */
public final class TableRoller {

    private static final System.Logger LOG = System.getLogger(TableRoller.class.getName());

    /** What happened to one day. */
    public enum DayStatus {
        /** Copied and logged by this run. */
        ROLLED,
        /** Already logged by an earlier run; nothing to do. */
        ALREADY_ROLLED,
        /** Data was already committed but the log entry was missing; the log was repaired. */
        LOG_REPAIRED,
        /** Not safe to archive yet (a writer may still be appending); a later run will retry. */
        NOT_FROZEN
    }

    public record DayResult(LocalDate day, DayStatus status, long rows, List<String> segments) {
    }

    /** Everything a run did, oldest day first. */
    public record RollResult(String table, List<DayResult> days) {
        public long totalRows() {
            return days.stream().mapToLong(DayResult::rows).sum();
        }

        public List<DayResult> rolled() {
            return days.stream().filter(d -> d.status() == DayStatus.ROLLED).toList();
        }
    }

    private final ColdConfig config;
    private final RollExecutor executor;

    public TableRoller(ColdConfig config, RollExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    public RollResult roll(String table) {
        return roll(table, ArenaInventory.todayUtc());
    }

    /**
     * Rolls every pending day of {@code table} strictly before {@code today}.
     *
     * @throws ArenaInventory.DayAlignmentException if a day's rows escape its day (aborts the run)
     * @throws ColdException                        if the store rejects a roll (aborts the run)
     */
    public RollResult roll(String table, LocalDate today) {
        SegmentDirectory dir = openExistingTableDir(table);
        List<ArenaInventory.DaySegments> pending = ArenaInventory.pendingDays(dir, today);
        if (pending.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG, "table {0}: no days before {1} to roll", table, today);
            return new RollResult(table, List.of());
        }

        ArenaSchema schema = readSchema(pending.get(0).segments().get(0));
        List<String> sortColumns = config.sortColumnsFor(table, schema.metadata().timeColumn());
        executor.ensureTable(table, schema, sortColumns);

        List<DayResult> results = new ArrayList<>();
        for (ArenaInventory.DaySegments day : pending) {
            DayResult result = rollDay(table, schema, sortColumns, dir, day);
            results.add(result);
            if (result.status() == DayStatus.NOT_FROZEN) {
                // I1: a day we cannot roll blocks every later day, or the watermark would jump over
                // it and both query sides would stop serving it.
                LOG.log(System.Logger.Level.INFO,
                        "table {0}: day {1} is not frozen yet; leaving it and all later days for the "
                                + "next run", table, day.day());
                break;
            }
        }
        return new RollResult(table, List.copyOf(results));
    }

    private DayResult rollDay(String table, ArenaSchema schema, List<String> sortColumns,
                              SegmentDirectory dir, ArenaInventory.DaySegments day) {
        List<String> names = day.fileNames();

        // Recovery branch 1: the log already records this day, so the roll completed. Whether the
        // segments are still on disk is R5's problem, not a correctness question.
        Optional<String> rolledBy = executor.rolledBy(table, day.day());
        if (rolledBy.isPresent()) {
            String owner = rolledBy.get();
            String mine = config.arenaBaseDir().resolve(table).toAbsolutePath().normalize().toString();
            if (owner != null && !owner.equals(mine)) {
                // Someone else's day, not ours. Skipping it would silently strand this arena's rows
                // — never archived, never reclaimed, memory growing with nothing but "rolled 0 days"
                // to show for it. Rolling it anyway would duplicate the day in history. Neither is
                // acceptable, so stop and make the operator resolve the collision.
                throw new ColdException("table " + table + " day " + day.day()
                        + " was already rolled from a different arena (" + owner + ", not " + mine
                        + "). Two arenas must not share one catalog table: point this roller at a "
                        + "different namespace, or clear that arena's roll-log entries and data.");
            }
            LOG.log(System.Logger.Level.DEBUG, "table {0}: day {1} already rolled", table, day.day());
            return new DayResult(day.day(), DayStatus.ALREADY_ROLLED, 0, names);
        }

        String timeColumn = schema.metadata().timeColumn();

        // Recovery branch 2: data is present but unlogged — a previous run died between committing
        // the data and recording it. One INSERT is one Iceberg snapshot, so any row for the day means
        // the whole day landed; repair the log rather than rolling it again (which would duplicate).
        if (executor.hasDataFor(table, timeColumn, day.day())) {
            long rows = executor.countDataFor(table, timeColumn, day.day());
            LOG.log(System.Logger.Level.WARNING,
                    "table {0}: day {1} has {2} committed rows but no roll-log entry — a previous run "
                            + "died mid-commit; repairing the log instead of re-rolling",
                    table, day.day(), rows);
            executor.logDayOnly(table, day.day(), rows, names);
            return new DayResult(day.day(), DayStatus.LOG_REPAIRED, rows, names);
        }

        // Only now does it matter whether the writer is finished with these segments.
        if (!ArenaInventory.isFrozen(dir, day, config.livenessProbe())) {
            return new DayResult(day.day(), DayStatus.NOT_FROZEN, 0, names);
        }

        ArenaInventory.verifyDayAlignment(day); // I2 — throws, aborting the run

        long rows = executor.rollDay(table, schema, day.day(), day.segments(), sortColumns);
        LOG.log(System.Logger.Level.INFO, "table {0}: rolled day {1} ({2} rows from {3} segment(s))",
                table, day.day(), rows, names.size());
        return new DayResult(day.day(), DayStatus.ROLLED, rows, names);
    }

    /** The cutover: realtime data starts here, historical data ends before it. Empty if nothing is
     *  rolled yet, meaning every row still comes from the arena. */
    public Optional<LocalDate> cutoverDay(String table) {
        return executor.highestRolledDay(table).map(d -> d.plusDays(1));
    }

    /**
     * Opens a table directory without creating it — {@code new SegmentDirectory(...)} would make the
     * directory as a side effect, quietly inventing an empty table for a typo'd name.
     */
    private SegmentDirectory openExistingTableDir(String table) {
        Path tableDir = config.arenaBaseDir().resolve(table);
        if (!java.nio.file.Files.isDirectory(tableDir)) {
            throw new ColdException("no arena table directory at " + tableDir);
        }
        return new SegmentDirectory(config.arenaBaseDir(), table);
    }

    private static ArenaSchema readSchema(Path segment) {
        try (SnapshotReader reader = SnapshotReader.open(segment)) {
            return reader.schema();
        }
    }
}
