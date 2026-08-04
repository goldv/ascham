package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
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
     *                       error) or duplicate intervals in history.
     */
    void ensureTable(String table, ArenaSchema schema, List<String> sortColumns);

    /**
     * The instant {@code table} is fully rolled through, or empty if none (or the table does not
     * exist yet). This is the watermark: every row before it is committed (rolling is ascending and
     * gapless, I1), and the cutover between historical and realtime data sits exactly on it. Read
     * from the table's own metadata, so it survives snapshot expiration and there is no side log to
     * lose.
     */
    Optional<Instant> rolledThrough(String table);

    /**
     * Copies one roll interval out of the given segments into the historical table: consecutive
     * segments are grouped into parquet files, each group sorted by {@code sortColumns}, and the
     * whole interval — data files, segment provenance in the snapshot summary, and the advanced
     * watermark — commits as one atomic transaction. A crash mid-interval therefore commits
     * nothing: the next run simply rolls the interval again.
     *
     * @return rows written
     */
    long rollInterval(String table, ArenaSchema schema, Instant intervalStart, Instant intervalEnd,
                      List<Path> segments, List<String> sortColumns);

    @Override
    void close();
}
