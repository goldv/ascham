package io.ito.arena.schema;

import java.util.Optional;

/**
 * Parsed schema-level {@code arena.*} metadata.
 *
 * @param table         table name ({@code arena.table})
 * @param schemaVersion integer version, bumped on any change ({@code arena.schema_version})
 * @param batchRows     target rows per sealed batch ({@code arena.batch_rows}, default 65536)
 * @param timeColumn    name of the timestamp column used for time-range batch pruning
 *                      ({@code arena.time_column}); drives the catalog {@code ts_min}/{@code ts_max}
 * @param statsColumn   optional integer column used for value-range batch pruning
 *                      ({@code arena.stats_column}); drives {@code stat_min}/{@code stat_max}
 */
public record ArenaMetadata(
        String table,
        int schemaVersion,
        int batchRows,
        String timeColumn,
        Optional<String> statsColumn) {
}
