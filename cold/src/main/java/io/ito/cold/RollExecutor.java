package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The historical store, as the roll protocol needs it. {@link TableRoller} owns the coordination
 * (what to roll, in what order, what is safe); this owns moving and committing the bytes.
 *
 * <p>Split out as an interface so the engine stays swappable: v1 embeds DuckDB, and a pure-Java
 * Iceberg writer could replace it without touching the protocol (docs/cold-tier-design-plan.md §5).
 */
public interface RollExecutor extends AutoCloseable {

    /** Creates the namespaces, the target table, and the roll log if they do not already exist. */
    void ensureTable(String table, ArenaSchema schema, List<String> sortColumns);

    /**
     * The highest day this arena has recorded as rolled for {@code table}, or empty if none. This is
     * the watermark: the cutover between historical and realtime data is the start of the following
     * day. Scoped to this arena, for the same reason as {@link #rolledBy}.
     */
    Optional<LocalDate> highestRolledDay(String table);

    /**
     * The arena directory that archived {@code (table, day)}, or empty if no run has.
     *
     * <p>Returns *who* rolled it rather than a bare yes/no, because a day logged by a different
     * arena is a misconfiguration, not a completed day: treating it as done would silently strand
     * this arena's rows — never archived, never reclaimed, memory growing with no error.
     */
    Optional<String> rolledBy(String table, LocalDate day);

    /**
     * Whether the table already holds data for {@code day}. Used only in recovery, to tell "the roll
     * committed but the log entry was lost" from "the roll never happened".
     */
    boolean hasDataFor(String table, String timeColumn, LocalDate day);

    /** Rows already present for {@code day} — used to repair a lost log entry with a true count. */
    long countDataFor(String table, String timeColumn, LocalDate day);

    /**
     * Copies one day out of the given segments into the historical table and records it in the roll
     * log — in a single transaction, so the log can never claim a day the data does not have.
     *
     * @return rows written
     */
    long rollDay(String table, ArenaSchema schema, LocalDate day, List<Path> segments,
                 List<String> sortColumns);

    /** Records a day in the roll log without copying data — recovery only, when the data is already
     *  committed but the log entry was lost. */
    void logDayOnly(String table, LocalDate day, long rows, List<String> segmentNames);

    /**
     * Days whose archive commit is older than {@code grace}, with the segment file names each was
     * built from — the only segments that may be reclaimed, and the exact set (invariant I3).
     *
     * <p>Age is evaluated against the store's own clock, so a skewed roller clock can never make a
     * just-committed day look reclaimable.
     */
    List<ReclaimableDay> reclaimable(String table, Duration grace);

    /**
     * A rolled day old enough to have its arena segments released.
     *
     * @param arenaDir the arena table directory the segments were read from. Segment names are only
     *                 unique within one arena, so reclamation must confirm the log entry refers to
     *                 the arena it is about to delete from — two arenas feeding one catalog would
     *                 otherwise let one of them delete the other's un-archived days.
     */
    record ReclaimableDay(LocalDate day, List<String> segmentNames, String arenaDir) {
    }

    @Override
    void close();
}
