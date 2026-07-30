package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
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
     * The highest day recorded as rolled for {@code table}, or empty if none. This is the watermark:
     * the cutover between historical and realtime data is the start of the following day.
     */
    Optional<LocalDate> highestRolledDay(String table);

    /** Whether {@code (table, day)} already has a roll-log entry. */
    boolean isDayLogged(String table, LocalDate day);

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

    @Override
    void close();
}
