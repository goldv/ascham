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
 *   <li><b>I3 — reclamation is separate.</b> This class never deletes a segment. Unlinking is a
 *       standalone utility's job, gated on the archive being durable — the roll records segment
 *       provenance in each commit's snapshot summary for it, and nothing more.</li>
 * </ul>
 *
 * <p>Rolling is idempotent. If a run dies part-way, the next one re-derives what to do from the
 * store itself — there is no local state to lose or reconcile.
 */
public final class TableRoller {

    private static final System.Logger LOG = System.getLogger(TableRoller.class.getName());

    /** What happened to one day. */
    public enum DayStatus {
        /** Copied and committed by this run. */
        ROLLED,
        /** At or below the table's watermark — committed by an earlier run; nothing to do. */
        ALREADY_ROLLED,
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
        List<String> sortColumns = config.sortColumnsFor(table, schema);
        // Also verifies this arena owns the table — a foreign table aborts before any day is
        // touched, rather than surfacing day by day as it did under the roll log.
        executor.ensureTable(table, schema, sortColumns);
        Optional<LocalDate> watermark = executor.highestRolledDay(table);

        List<DayResult> results = new ArrayList<>();
        for (ArenaInventory.DaySegments day : pending) {
            DayResult result = rollDay(table, schema, sortColumns, dir, day, watermark);
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
                              SegmentDirectory dir, ArenaInventory.DaySegments day,
                              Optional<LocalDate> watermark) {
        List<String> names = day.fileNames();

        // At or below the watermark means the day is fully committed: a day's data files and the
        // advanced watermark land in one atomic transaction, so "data committed but unrecorded"
        // cannot exist and needs no repair branch. Whether the segments are still on disk is the
        // reclaim utility's concern, not a correctness question.
        if (watermark.isPresent() && !day.day().isAfter(watermark.get())) {
            LOG.log(System.Logger.Level.DEBUG, "table {0}: day {1} already rolled", table, day.day());
            return new DayResult(day.day(), DayStatus.ALREADY_ROLLED, 0, names);
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
