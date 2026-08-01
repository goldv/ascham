package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Everything the cold tier needs to roll a table: where the arena lives, where the historical data
 * goes, and the knobs that shape the written files.
 *
 * <p>The destination is one string and it decides the catalog: a local path or {@code file://} URI
 * rolls straight to disk through a serverless Hadoop catalog, an {@code http(s)://} URI is an
 * Iceberg REST catalog endpoint (object storage comes from the catalog's warehouse, e.g. S3). A
 * bare {@code s3://} warehouse is rejected — Hadoop-catalog commits are not atomic on object
 * stores; S3 data goes through a REST catalog.
 *
 * <p>Built with {@link #builder()}; only {@code arenaBaseDir} and {@code destination} have no
 * default.
 */
public final class ColdConfig {

    private final Path arenaBaseDir;
    private final String destination;
    private final String warehouseName;
    private final String namespace;
    private final S3Credentials s3;
    private final Map<String, List<String>> sortColumns;
    private final Map<String, String> catalogProperties;
    private final int segmentsPerFile;
    private final long targetFileSizeBytes;
    private final Duration livenessProbe;

    /** S3-compatible object-store credentials, mapped to S3FileIO properties; may be absent when
     *  the catalog vends credentials (the dev stack does — see dev/README.md). */
    public record S3Credentials(String endpoint, String keyId, String secret, boolean useSsl, boolean pathStyle) {
    }

    private ColdConfig(Builder b) {
        this.arenaBaseDir = Objects.requireNonNull(b.arenaBaseDir, "arenaBaseDir");
        this.destination = Objects.requireNonNull(b.destination, "destination");
        this.warehouseName = b.warehouseName;
        this.namespace = b.namespace;
        this.s3 = b.s3;
        this.sortColumns = Map.copyOf(b.sortColumns);
        this.catalogProperties = Map.copyOf(b.catalogProperties);
        if (b.segmentsPerFile < 1) {
            throw new IllegalArgumentException("segmentsPerFile must be >= 1, got " + b.segmentsPerFile);
        }
        this.segmentsPerFile = b.segmentsPerFile;
        if (b.targetFileSizeBytes < 1) {
            throw new IllegalArgumentException("targetFileSizeBytes must be positive");
        }
        this.targetFileSizeBytes = b.targetFileSizeBytes;
        this.livenessProbe = b.livenessProbe;
    }

    public Path arenaBaseDir() {
        return arenaBaseDir;
    }

    /** Local warehouse path, {@code file://} URI, or {@code http(s)://} REST catalog endpoint. */
    public String destination() {
        return destination;
    }

    /** Warehouse name sent to a REST catalog (default {@code ito}); unused for local paths. */
    public String warehouseName() {
        return warehouseName;
    }

    /** Namespace holding the rolled tables (default {@code ito}). */
    public String namespace() {
        return namespace;
    }

    public S3Credentials s3() {
        return s3;
    }

    /** Extra Iceberg catalog properties, applied last — the escape hatch for anything the typed
     *  surface does not cover. */
    public Map<String, String> catalogProperties() {
        return catalogProperties;
    }

    /**
     * Consecutive same-day segments per rolled parquet file. Segments are the file-size dial: at
     * ~115 MB per segment, 2 gives ~230 MB files — the docs' 128–512 MB guidance scales with this
     * and the segment capacity.
     */
    public int segmentsPerFile() {
        return segmentsPerFile;
    }

    /** Written as {@code write.target-file-size-bytes} on created tables (default 512 MB). Advisory
     *  for engines that compact later; the roll's actual file size comes from segmentsPerFile. */
    public long targetFileSizeBytes() {
        return targetFileSizeBytes;
    }

    /**
     * Columns each table's rolled files are sorted by, e.g. {@code quotes -> [sym, ts]}. Tables
     * with no configured entry fall back to the schema's own {@code arena.sort_key} declaration,
     * then to the arena {@code time_column} alone. Sorting is what gives Parquet min/max stats
     * their kdb-parted-like symbol-skipping shape.
     */
    public List<String> sortColumnsFor(String table, ArenaSchema schema) {
        List<String> configured = sortColumns.get(table);
        if (configured != null) {
            return configured;
        }
        TreeMap<Integer, String> byKey = new TreeMap<>();
        schema.columns().forEach(c -> c.sortKey().ifPresent(k -> byKey.put(k, c.name())));
        if (!byKey.isEmpty()) {
            return List.copyOf(new ArrayList<>(byKey.values()));
        }
        return List.of(schema.metadata().timeColumn());
    }

    /**
     * How long to watch a heartbeat before concluding the writer is gone. Only consulted for the
     * newest segment of a pending day, which a live writer should already have rotated away from
     * (arena's rotate-on-heartbeat, R2) — so this path means "the writer probably died".
     */
    public Duration livenessProbe() {
        return livenessProbe;
    }

    /** The arena table directory segments are read from — recorded in the historical table so no
     *  other arena can be confused with this one. */
    public String arenaDirOf(String table) {
        return arenaBaseDir.resolve(table).toAbsolutePath().normalize().toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path arenaBaseDir;
        private String destination;
        private String warehouseName = "ito";
        private String namespace = "ito";
        private S3Credentials s3;
        private Map<String, List<String>> sortColumns = Map.of();
        private Map<String, String> catalogProperties = Map.of();
        private int segmentsPerFile = 1;
        private long targetFileSizeBytes = 512L << 20;
        private Duration livenessProbe = Duration.ofSeconds(5);

        public Builder arenaBaseDir(Path v) {
            this.arenaBaseDir = v;
            return this;
        }

        public Builder destination(String v) {
            this.destination = v;
            return this;
        }

        public Builder warehouseName(String v) {
            this.warehouseName = v;
            return this;
        }

        public Builder namespace(String v) {
            this.namespace = v;
            return this;
        }

        public Builder s3(S3Credentials v) {
            this.s3 = v;
            return this;
        }

        public Builder sortColumns(Map<String, List<String>> v) {
            this.sortColumns = v;
            return this;
        }

        public Builder catalogProperties(Map<String, String> v) {
            this.catalogProperties = v;
            return this;
        }

        public Builder segmentsPerFile(int v) {
            this.segmentsPerFile = v;
            return this;
        }

        public Builder targetFileSizeBytes(long v) {
            this.targetFileSizeBytes = v;
            return this;
        }

        public Builder livenessProbe(Duration v) {
            this.livenessProbe = v;
            return this;
        }

        public ColdConfig build() {
            return new ColdConfig(this);
        }
    }
}
