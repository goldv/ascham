// GENERATED from spec/format-manifest.toml (sha256 c85825dcb549) by spec/generate_format.py — DO NOT EDIT.
// Regenerate with: python3 spec/generate_format.py --lang java --repo .
package io.ascham.schema;

import java.util.Set;

/**
 * The {@code ascham.*} metadata keys carried in the Arrow schema's {@code custom_metadata} (schema
 * level) and each field's metadata (field level). Arrow schemas cannot express capacity or pruning
 * intent, so it is carried here rather than in a sidecar file — one artifact, and generic Arrow
 * tooling can still read it.
 *
 * <p>The prefix was confirmed at the ascham rename; changing it again is a format break, since the
 * keys are part of the canonical schema bytes hashed into every segment header.
 */
public final class MetadataKeys {

    /** Prefix owned by this module; any unknown {@code ascham.*} key is a validation error. */
    public static final String PREFIX = "ascham.";

    // Schema-level.
    public static final String TABLE = "ascham.table";
    public static final String SCHEMA_VERSION = "ascham.schema_version";
    public static final String BATCH_ROWS = "ascham.batch_rows";
    public static final String TIME_COLUMN = "ascham.time_column";
    public static final String STATS_COLUMN = "ascham.stats_column";

    // Field-level.
    public static final String VARLEN_BYTES = "ascham.varlen_bytes";
    public static final String SORT_KEY = "ascham.sort_key";
    public static final String FAMILY = "ascham.family";
    public static final String REF = "ascham.ref";

    /** Default target rows per sealed batch when {@link #BATCH_ROWS} is absent. */
    public static final int DEFAULT_BATCH_ROWS = 65536;

    /** Default column family when {@link #FAMILY} is absent. */
    public static final String DEFAULT_FAMILY = "base";

    static final Set<String> SCHEMA_LEVEL = Set.of(
            TABLE, SCHEMA_VERSION, BATCH_ROWS, TIME_COLUMN, STATS_COLUMN);

    static final Set<String> FIELD_LEVEL = Set.of(
            VARLEN_BYTES, SORT_KEY, FAMILY, REF);

    private MetadataKeys() {
    }
}
