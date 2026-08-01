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
 * <p>Split out as an interface so the protocol stays testable without a warehouse — the unit tests
 * drive {@link TableRoller} through a fake; {@link IcebergRollExecutor} is the real store.
 */
public interface RollExecutor extends AutoCloseable {

    /**
     * Loads or creates the historical table: schema, day partition on the time column, declared
     * sort order, v3 format and write properties — and verifies this arena owns it.
     *
     * @throws ColdException if the table exists but belongs to a different arena directory. Two
     *                       arenas must not share one table: treating a foreign table as ours would
     *                       either strand this arena's rows (never archived, memory growing with no
     *                       error) or duplicate days in history.
     */
    void ensureTable(String table, ArenaSchema schema, List<String> sortColumns);

    /**
     * The highest day recorded as fully rolled for {@code table}, or empty if none (or the table
     * does not exist yet). This is the watermark: days at or below it are done (rolling is
     * ascending and gapless, I1), and the cutover between historical and realtime data is the start
     * of the following day. Read from the table's own metadata, so it survives snapshot expiration
     * and there is no side log to lose.
     */
    Optional<LocalDate> highestRolledDay(String table);

    /**
     * Copies one day out of the given segments into the historical table: consecutive segments are
     * grouped into parquet files, each group sorted by {@code sortColumns}, and the whole day —
     * data files, segment provenance in the snapshot summary, and the advanced watermark — commits
     * as one atomic transaction. A crash mid-day therefore commits nothing: the next run simply
     * rolls the day again.
     *
     * @return rows written
     */
    long rollDay(String table, ArenaSchema schema, LocalDate day, List<Path> segments,
                 List<String> sortColumns);

    @Override
    void close();
}
