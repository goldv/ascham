package io.ito.arena.schema;

import java.util.Set;

/**
 * The {@code arena.*} metadata keys carried in the Arrow schema's {@code custom_metadata} (schema
 * level) and each field's metadata (field level). Arrow schemas cannot express capacity or pruning
 * intent, so it is carried here rather than in a sidecar file — one artifact, and generic Arrow
 * tooling can still read it.
 *
 * <p>NOTE: the {@code arena.*} prefix is a placeholder pending confirmation before M1 (see
 * docs/arena-design-plan.md §1). Changing it is cheap now and a format break later.
 */
public final class MetadataKeys {

    /** Prefix owned by this module; any unknown {@code arena.*} key is a validation error. */
    public static final String PREFIX = "arena.";

    // Schema-level.
    public static final String TABLE = "arena.table";
    public static final String SCHEMA_VERSION = "arena.schema_version";
    public static final String BATCH_ROWS = "arena.batch_rows";
    public static final String TIME_COLUMN = "arena.time_column";
    public static final String STATS_COLUMN = "arena.stats_column";

    // Field-level.
    public static final String VARLEN_BYTES = "arena.varlen_bytes";
    public static final String SORT_KEY = "arena.sort_key";
    public static final String FAMILY = "arena.family";
    public static final String REF = "arena.ref";

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
