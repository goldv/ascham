package io.ascham.schema;

import java.util.Optional;

/**
 * Parsed schema-level {@code ascham.*} metadata.
 *
 * @param table         table name ({@code ascham.table})
 * @param schemaVersion integer version, bumped on any change ({@code ascham.schema_version})
 * @param batchRows     target rows per sealed batch ({@code ascham.batch_rows}, default 65536)
 * @param timeColumn    name of the timestamp column used for time-range batch pruning
 *                      ({@code ascham.time_column}); drives the catalog {@code ts_min}/{@code ts_max}
 * @param statsColumn   optional integer column used for value-range batch pruning
 *                      ({@code ascham.stats_column}); drives {@code stat_min}/{@code stat_max}
 */
public record ArenaMetadata(
        String table,
        int schemaVersion,
        int batchRows,
        String timeColumn,
        Optional<String> statsColumn) {
}
