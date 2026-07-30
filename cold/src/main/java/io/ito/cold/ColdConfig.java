package io.ito.cold;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the cold tier needs to roll a table: where the arena lives, how to reach the Iceberg
 * catalog, and the knobs that shape the written files.
 *
 * <p>Built with {@link #builder()}; only {@code arenaBaseDir} and the catalog coordinates have no
 * sensible default.
 */
public final class ColdConfig {

    private final Path arenaBaseDir;
    private final Path arenaExtension;
    private final String catalogEndpoint;
    private final String warehouse;
    private final String catalogAlias;
    private final String namespace;
    private final String metaNamespace;
    private final String rollLogTable;
    private final S3Credentials s3;
    private final Map<String, List<String>> sortColumns;
    private final String memoryLimit;
    private final Path tempDirectory;
    private final Duration livenessProbe;

    /** S3-compatible object-store credentials for the client-side secret; may be absent when the
     *  catalog vends credentials (the dev stack does — see dev/README.md). */
    public record S3Credentials(String endpoint, String keyId, String secret, boolean useSsl, boolean pathStyle) {
    }

    private ColdConfig(Builder b) {
        this.arenaBaseDir = Objects.requireNonNull(b.arenaBaseDir, "arenaBaseDir");
        this.arenaExtension = Objects.requireNonNull(b.arenaExtension, "arenaExtension");
        this.catalogEndpoint = Objects.requireNonNull(b.catalogEndpoint, "catalogEndpoint");
        this.warehouse = Objects.requireNonNull(b.warehouse, "warehouse");
        this.catalogAlias = b.catalogAlias;
        this.namespace = b.namespace;
        this.metaNamespace = b.metaNamespace;
        this.rollLogTable = b.rollLogTable;
        this.s3 = b.s3;
        this.sortColumns = Map.copyOf(b.sortColumns);
        this.memoryLimit = b.memoryLimit;
        this.tempDirectory = b.tempDirectory;
        this.livenessProbe = b.livenessProbe;
    }

    public Path arenaBaseDir() {
        return arenaBaseDir;
    }

    public Path arenaExtension() {
        return arenaExtension;
    }

    public String catalogEndpoint() {
        return catalogEndpoint;
    }

    public String warehouse() {
        return warehouse;
    }

    /** Local alias the catalog is ATTACHed under (default {@code hist}). */
    public String catalogAlias() {
        return catalogAlias;
    }

    /** Namespace holding the rolled tables (default {@code ito}). */
    public String namespace() {
        return namespace;
    }

    /** Namespace holding the roll log (default {@code ito_meta}). */
    public String metaNamespace() {
        return metaNamespace;
    }

    public String rollLogTable() {
        return rollLogTable;
    }

    public S3Credentials s3() {
        return s3;
    }

    /**
     * Columns each table's rolled files are sorted by, e.g. {@code quotes -> [sym, ts]}. Tables
     * with no entry fall back to the arena {@code time_column} alone. Sorting is what gives Parquet
     * min/max stats their kdb-parted-like symbol-skipping shape.
     */
    public List<String> sortColumnsFor(String table, String timeColumn) {
        return sortColumns.getOrDefault(table, List.of(timeColumn));
    }

    /** DuckDB {@code memory_limit} for the roll session; the day sort spills beyond it. */
    public String memoryLimit() {
        return memoryLimit;
    }

    /** DuckDB {@code temp_directory} — where the external sort spills. Null leaves the default. */
    public Path tempDirectory() {
        return tempDirectory;
    }

    /**
     * How long to watch a heartbeat before concluding the writer is gone. Only consulted for the
     * newest segment of a pending day, which a live writer should already have rotated away from
     * (arena's rotate-on-heartbeat, R2) — so this path means "the writer probably died".
     */
    public Duration livenessProbe() {
        return livenessProbe;
    }

    public String qualified(String table) {
        return catalogAlias + "." + namespace + "." + table;
    }

    public String qualifiedRollLog() {
        return catalogAlias + "." + metaNamespace + "." + rollLogTable;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path arenaBaseDir;
        private Path arenaExtension;
        private String catalogEndpoint;
        private String warehouse;
        private String catalogAlias = "hist";
        private String namespace = "ito";
        private String metaNamespace = "ito_meta";
        private String rollLogTable = "roll_log";
        private S3Credentials s3;
        private Map<String, List<String>> sortColumns = Map.of();
        private String memoryLimit = "2GB";
        private Path tempDirectory;
        private Duration livenessProbe = Duration.ofSeconds(5);

        public Builder arenaBaseDir(Path v) {
            this.arenaBaseDir = v;
            return this;
        }

        public Builder arenaExtension(Path v) {
            this.arenaExtension = v;
            return this;
        }

        public Builder catalog(String endpoint, String warehouse) {
            this.catalogEndpoint = endpoint;
            this.warehouse = warehouse;
            return this;
        }

        public Builder catalogAlias(String v) {
            this.catalogAlias = v;
            return this;
        }

        public Builder namespace(String v) {
            this.namespace = v;
            return this;
        }

        public Builder metaNamespace(String v) {
            this.metaNamespace = v;
            return this;
        }

        public Builder rollLogTable(String v) {
            this.rollLogTable = v;
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

        public Builder memoryLimit(String v) {
            this.memoryLimit = v;
            return this;
        }

        public Builder tempDirectory(Path v) {
            this.tempDirectory = v;
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
