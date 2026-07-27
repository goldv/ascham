package io.ito.arena.schema;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Parsed field-level {@code arena.*} metadata for one column.
 *
 * @param name        the Arrow field name (for diagnostics and lookups)
 * @param varlenBytes byte capacity per batch for {@code Utf8}/{@code Binary} columns
 *                    ({@code arena.varlen_bytes}); required for varlen, absent otherwise
 * @param sortKey     optional sort-key ordinal ({@code arena.sort_key})
 * @param family      column family name ({@code arena.family}, default {@code base})
 * @param ref         for {@code Int32} columns, the ref-data table this code resolves against
 *                    ({@code arena.ref}); this module does not implement ref data
 */
public record ColumnMetadata(
        String name,
        OptionalLong varlenBytes,
        OptionalInt sortKey,
        String family,
        Optional<String> ref) {
}
