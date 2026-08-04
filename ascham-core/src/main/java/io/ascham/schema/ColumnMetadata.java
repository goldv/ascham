package io.ascham.schema;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Parsed field-level {@code ascham.*} metadata for one column.
 *
 * @param name        the Arrow field name (for diagnostics and lookups)
 * @param varlenBytes byte capacity per batch for {@code Utf8}/{@code Binary} columns
 *                    ({@code ascham.varlen_bytes}); required for varlen, absent otherwise
 * @param sortKey     optional sort-key ordinal ({@code ascham.sort_key})
 * @param family      column family name ({@code ascham.family}, default {@code base})
 * @param ref         for {@code Int32} columns, the ref-data table this code resolves against
 *                    ({@code ascham.ref}); this module does not implement ref data
 */
public record ColumnMetadata(
        String name,
        OptionalLong varlenBytes,
        OptionalInt sortKey,
        String family,
        Optional<String> ref) {
}
