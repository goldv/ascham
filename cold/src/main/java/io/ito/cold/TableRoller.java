package io.ito.cold;

import io.ito.arena.read.SnapshotReader;
import io.ito.arena.rotate.SegmentDirectory;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rolls one table's completed roll intervals out of the arena into the historical store, per the
 * protocol in docs/cold-tier-design-plan.md §4.
 *
 * <p>Three rules carry the correctness of the whole design:
 *
 * <ul>
 *   <li><b>I1 — ascending, gapless.</b> Intervals are rolled oldest first and the run stops at the
 *       first failure. Never rolling an interval before every earlier one is done is what lets a
 *       single "rolled through" instant stand in for the whole set; roll them out of order and a
 *       failed middle interval would be excluded by both the historical and the realtime side of a
 *       query.</li>
 *   <li><b>I2 — interval-alignment verified, not assumed.</b> A segment's rows must actually fall
 *       inside the interval its name declares; a violation aborts the table rather than silently
 *       splitting it.</li>
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

    /** What happened to one roll interval. */
    public enum IntervalStatus {
        /** Copied and committed by this run. */
        ROLLED,
        /** At or below the table's watermark — committed by an earlier run; nothing to do. */
        ALREADY_ROLLED,
        /** Not safe to archive yet (a writer may still be appending); a later run will retry. */
        NOT_FROZEN
    }

    public record IntervalResult(Instant start, Instant end, IntervalStatus status, long rows,
                                 List<String> segments) {
    }

    /** Everything a run did, oldest interval first. */
    public record RollResult(String table, List<IntervalResult> intervals) {
        public long totalRows() {
            return intervals.stream().mapToLong(IntervalResult::rows).sum();
        }

        public List<IntervalResult> rolled() {
            return intervals.stream().filter(i -> i.status() == IntervalStatus.ROLLED).toList();
        }
    }

    private final ColdConfig config;
    private final RollExecutor executor;

    public TableRoller(ColdConfig config, RollExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    public RollResult roll(String table) {
        return roll(table, Instant.now());
    }

    /**
     * Rolls every pending interval of {@code table} that is complete at {@code now}.
     *
     * @throws ArenaInventory.IntervalAlignmentException if a segment's rows escape its declared
     *                                                   interval (aborts the run)
     * @throws ColdException                             if the store rejects a roll (aborts the run)
     */
    public RollResult roll(String table, Instant now) {
        SegmentDirectory dir = openExistingTableDir(table);
        List<ArenaInventory.IntervalSegments> pending = ArenaInventory.pendingIntervals(dir, now);
        if (pending.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG, "table {0}: no intervals complete before {1} to roll",
                    table, now);
            return new RollResult(table, List.of());
        }

        ArenaSchema schema = readSchema(pending.get(0).segments().get(0).path());
        List<String> sortColumns = config.sortColumnsFor(table, schema);
        // Also verifies this arena owns the table — a foreign table aborts before any interval is
        // touched, rather than surfacing interval by interval as it did under the roll log.
        executor.ensureTable(table, schema, sortColumns);
        Optional<Instant> watermark = executor.rolledThrough(table);

        List<IntervalResult> results = new ArrayList<>();
        for (ArenaInventory.IntervalSegments unit : pending) {
            IntervalResult result = rollInterval(table, schema, sortColumns, dir, unit, watermark);
            results.add(result);
            if (result.status() == IntervalStatus.NOT_FROZEN) {
                // I1: an interval we cannot roll blocks every later one, or the watermark would
                // jump over it and both query sides would stop serving it.
                LOG.log(System.Logger.Level.INFO,
                        "table {0}: interval [{1}, {2}) is not frozen yet; leaving it and all later "
                                + "intervals for the next run", table, unit.start(), unit.end());
                break;
            }
        }
        return new RollResult(table, List.copyOf(results));
    }

    private IntervalResult rollInterval(String table, ArenaSchema schema, List<String> sortColumns,
                                        SegmentDirectory dir, ArenaInventory.IntervalSegments unit,
                                        Optional<Instant> watermark) {
        List<String> names = unit.fileNames();

        // At or below the watermark means the interval is fully committed: its data files and the
        // advanced watermark land in one atomic transaction, so "data committed but unrecorded"
        // cannot exist and needs no repair branch. Whether the segments are still on disk is the
        // reclaim utility's concern, not a correctness question.
        if (watermark.isPresent() && !unit.end().isAfter(watermark.get())) {
            LOG.log(System.Logger.Level.DEBUG, "table {0}: interval [{1}, {2}) already rolled",
                    table, unit.start(), unit.end());
            return new IntervalResult(unit.start(), unit.end(), IntervalStatus.ALREADY_ROLLED, 0, names);
        }

        // A merged unit can straddle the watermark: after a mid-interval cycle change, a
        // longer-cycle segment overlaps intervals an earlier run already committed. Segments whose
        // own declared interval is below the watermark were part of those commits — re-rolling them
        // would duplicate rows — so only the ones past the watermark are copied.
        List<SegmentDirectory.SegmentName> remaining = unit.segments();
        if (watermark.isPresent()) {
            remaining = remaining.stream().filter(s -> s.end().isAfter(watermark.get())).toList();
            if (remaining.size() < unit.segments().size()) {
                LOG.log(System.Logger.Level.INFO,
                        "table {0}: interval [{1}, {2}) — skipping {3} of {4} segment(s) already "
                                + "committed at or below the {5} watermark",
                        table, unit.start(), unit.end(), unit.segments().size() - remaining.size(),
                        unit.segments().size(), watermark.get());
            }
        }
        ArenaInventory.IntervalSegments toRoll =
                new ArenaInventory.IntervalSegments(unit.start(), unit.end(), remaining);

        // Only now does it matter whether the writer is finished with these segments.
        if (!ArenaInventory.isFrozen(dir, unit, config.livenessProbe())) {
            return new IntervalResult(unit.start(), unit.end(), IntervalStatus.NOT_FROZEN, 0, names);
        }

        ArenaInventory.verifyIntervalAlignment(toRoll); // I2 — throws, aborting the run

        long rows = executor.rollInterval(table, schema, unit.start(), unit.end(), toRoll.paths(),
                sortColumns);
        LOG.log(System.Logger.Level.INFO,
                "table {0}: rolled interval [{1}, {2}) ({3} rows from {4} segment(s))",
                table, unit.start(), unit.end(), rows, toRoll.segments().size());
        return new IntervalResult(unit.start(), unit.end(), IntervalStatus.ROLLED, rows,
                toRoll.fileNames());
    }

    /** The cutover: realtime data starts here, historical data ends before it. Empty if nothing is
     *  rolled yet, meaning every row still comes from the arena. */
    public Optional<Instant> cutover(String table) {
        return executor.rolledThrough(table);
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
