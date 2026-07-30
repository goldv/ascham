package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;

/**
 * Rolls days into Iceberg with an embedded DuckDB: one engine reads the arena through the native
 * {@code arena} extension, sorts the day, encodes Parquet, and commits the Iceberg snapshot.
 *
 * <p>The session loads two extensions at once — the locally-built unsigned {@code arena} and the
 * signed core {@code iceberg} — and attaches the catalog. That combination is what makes the roll a
 * single SQL statement over a zero-copy scan of shared memory.
 *
 * <p>Not thread-safe: one instance drives one connection, and {@link TableRoller} is single-threaded
 * per table by design.
 */
public final class DuckDbRollExecutor implements RollExecutor {

    private static final System.Logger LOG = System.getLogger(DuckDbRollExecutor.class.getName());

    private final ColdConfig config;
    private final Connection connection;

    public DuckDbRollExecutor(ColdConfig config) {
        this.config = config;
        try {
            Properties props = new Properties();
            // The arena extension is built locally and unsigned; DuckDB refuses to load it otherwise.
            props.setProperty("allow_unsigned_extensions", "true");
            this.connection = DriverManager.getConnection("jdbc:duckdb:", props);
        } catch (SQLException e) {
            throw new ColdException("failed to open the embedded DuckDB session", e);
        }
        try {
            initSession();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void initSession() {
        exec("LOAD '" + config.arenaExtension().toAbsolutePath() + "'");
        exec("INSTALL iceberg");
        exec("LOAD iceberg");
        // httpfs owns the S3 secret type, so it must be loaded before CREATE SECRET.
        exec("INSTALL httpfs");
        exec("LOAD httpfs");

        ColdConfig.S3Credentials s3 = config.s3();
        if (s3 != null) {
            exec("CREATE OR REPLACE SECRET cold_s3 (TYPE S3, KEY_ID " + literal(s3.keyId())
                    + ", SECRET " + literal(s3.secret()) + ", ENDPOINT " + literal(s3.endpoint())
                    + ", URL_STYLE " + literal(s3.pathStyle() ? "path" : "vhost")
                    + ", USE_SSL " + s3.useSsl() + ")");
        }
        if (config.memoryLimit() != null) {
            exec("SET memory_limit = " + literal(config.memoryLimit()));
        }
        if (config.tempDirectory() != null) {
            exec("SET temp_directory = " + literal(config.tempDirectory().toAbsolutePath().toString()));
        }
        // AUTHORIZATION_TYPE 'none' matches the dev catalog; ATTACH defaults to oauth2 and would fail.
        exec("ATTACH IF NOT EXISTS " + literal(config.warehouse()) + " AS " + quoteIdent(config.catalogAlias())
                + " (TYPE iceberg, ENDPOINT " + literal(config.catalogEndpoint())
                + ", AUTHORIZATION_TYPE 'none')");
    }

    @Override
    public void ensureTable(String table, ArenaSchema schema, List<String> sortColumns) {
        String alias = quoteIdent(config.catalogAlias());
        exec("CREATE SCHEMA IF NOT EXISTS " + alias + "." + quoteIdent(config.namespace()));
        exec("CREATE SCHEMA IF NOT EXISTS " + alias + "." + quoteIdent(config.metaNamespace()));

        // 'format-version' must be a *quoted* property key: an unquoted format_version is silently
        // ignored and the table is created as v2, which then rejects TIMESTAMP_NS columns (R1).
        exec("CREATE TABLE IF NOT EXISTS " + config.qualified(table)
                + " (" + TypeMapping.ddlColumnList(schema) + ") WITH ('format-version' = '3')");
        exec("CREATE TABLE IF NOT EXISTS " + config.qualifiedRollLog()
                + " (table_name VARCHAR, day DATE, rows BIGINT, segments VARCHAR, arena_dir VARCHAR,"
                + "  committed_at TIMESTAMP)"
                + " WITH ('format-version' = '3')");

        // Partitioning is not accepted inside CREATE TABLE (parser error) — it is a separate ALTER,
        // and it is idempotent, so re-running the roller is safe.
        String timeColumn = schema.metadata().timeColumn();
        exec("ALTER TABLE " + config.qualified(table)
                + " SET PARTITIONED BY (day(" + TypeMapping.quote(timeColumn) + "))");
        // Sort order cannot be declared through DuckDB (SET SORTED BY is "Not implemented", R1), so
        // physical sortedness comes from the ORDER BY in every roll instead. Recorded, not enforced.
        LOG.log(System.Logger.Level.DEBUG, "table {0} rolls sorted by {1}", table, sortColumns);
    }

    @Override
    public Optional<LocalDate> highestRolledDay(String table) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT max(day) FROM " + config.qualifiedRollLog()
                     + " WHERE table_name = " + literal(table)
                     + " AND arena_dir = " + literal(arenaDirOf(table)))) {
            if (rs.next()) {
                java.sql.Date day = rs.getDate(1);
                return day == null ? Optional.empty() : Optional.of(day.toLocalDate());
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new ColdException("failed to read the roll-log watermark for " + table, e);
        }
    }

    @Override
    public Optional<String> rolledBy(String table, LocalDate day) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT arena_dir FROM " + config.qualifiedRollLog()
                     + " WHERE table_name = " + literal(table)
                     + " AND day = DATE " + literal(day.toString()) + " LIMIT 1")) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        } catch (SQLException e) {
            throw new ColdException("failed to read the roll log for " + table + " day " + day, e);
        }
    }

    @Override
    public boolean hasDataFor(String table, String timeColumn, LocalDate day) {
        // Partition-pruned: only the day's files are opened, and only to answer "any row?".
        return count("SELECT count(*) FROM (SELECT 1 FROM " + config.qualified(table)
                + " WHERE " + dayPredicate(timeColumn, day) + " LIMIT 1)") > 0;
    }

    @Override
    public long rollDay(String table, ArenaSchema schema, LocalDate day, List<Path> segments,
                        List<String> sortColumns) {
        String select = "SELECT " + TypeMapping.selectList(schema)
                + " FROM arena_scan([" + pathList(segments) + "])"
                // Belt-and-braces: day-alignment is verified up front, so this predicate should never
                // drop a row. It is here so that even a verification bug cannot smear rows across
                // partitions or desynchronise the watermark from the data (§3.2, I2).
                + " WHERE " + dayPredicate(schema.metadata().timeColumn(), day)
                + " ORDER BY " + orderBy(sortColumns);

        String segmentNames = segments.stream().map(p -> p.getFileName().toString())
                .reduce((a, b) -> a + "," + b).orElse("");

        try {
            connection.setAutoCommit(false);
            long rows;
            try (Statement st = connection.createStatement()) {
                rows = st.executeLargeUpdate("INSERT INTO " + config.qualified(table) + " " + select);
                st.executeUpdate("INSERT INTO " + config.qualifiedRollLog() + " VALUES ("
                        + literal(table) + ", DATE " + literal(day.toString()) + ", " + rows + ", "
                        + literal(segmentNames) + ", " + literal(arenaDirOf(table)) + ", now())");
            }
            connection.commit();
            return rows;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new ColdException("failed to roll " + table + " day " + day, e);
        } finally {
            restoreAutoCommit();
        }
    }

    @Override
    public List<ReclaimableDay> reclaimable(String table, java.time.Duration grace) {
        // Age is computed in SQL against the store's own now(), so the roller's local clock — which
        // may be skewed from the catalog's — cannot shorten the grace window.
        String sql = "SELECT day, segments, arena_dir FROM " + config.qualifiedRollLog()
                + " WHERE table_name = " + literal(table)
                + " AND committed_at <= now() - INTERVAL " + grace.toSeconds() + " SECOND"
                + " ORDER BY day";
        List<ReclaimableDay> out = new java.util.ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String segments = rs.getString(2);
                List<String> names = segments == null || segments.isBlank()
                        ? List.of()
                        : List.of(segments.split(","));
                out.add(new ReclaimableDay(rs.getDate(1).toLocalDate(), names, rs.getString(3)));
            }
        } catch (SQLException e) {
            throw new ColdException("failed to list reclaimable days for " + table, e);
        }
        return out;
    }

    @Override
    public void logDayOnly(String table, LocalDate day, long rows, List<String> segmentNames) {
        exec("INSERT INTO " + config.qualifiedRollLog() + " VALUES (" + literal(table)
                + ", DATE " + literal(day.toString()) + ", " + rows + ", "
                + literal(String.join(",", segmentNames)) + ", " + literal(arenaDirOf(table)) + ", now())");
    }

    @Override
    public long countDataFor(String table, String timeColumn, LocalDate day) {
        return count("SELECT count(*) FROM " + config.qualified(table)
                + " WHERE " + dayPredicate(timeColumn, day));
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "failed to close the DuckDB session", e);
        }
    }

    /** Escape hatch for tests and diagnostics: runs SQL on the configured session. */
    public Connection connection() {
        return connection;
    }

    // --- helpers ---

    /** The arena table directory this executor reads from — recorded so reclamation can verify it. */
    private String arenaDirOf(String table) {
        return config.arenaBaseDir().resolve(table).toAbsolutePath().normalize().toString();
    }

    /** The half-open day window on a table's time column, as used by the roll and by recovery. */
    private static String dayPredicate(String timeColumn, LocalDate day) {
        String col = TypeMapping.quote(timeColumn);
        return col + " >= " + timestampLiteral(day) + " AND " + col + " < " + timestampLiteral(day.plusDays(1));
    }

    private void exec(String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new ColdException("failed: " + sql, e);
        }
    }

    private long count(String sql) {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new ColdException("failed: " + sql, e);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "rollback failed", e);
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "failed to restore auto-commit", e);
        }
    }

    private static String orderBy(List<String> sortColumns) {
        StringJoiner joiner = new StringJoiner(", ");
        sortColumns.forEach(c -> joiner.add(TypeMapping.quote(c)));
        return joiner.toString();
    }

    private static String pathList(List<Path> segments) {
        StringJoiner joiner = new StringJoiner(", ");
        segments.forEach(p -> joiner.add(literal(p.toAbsolutePath().toString())));
        return joiner.toString();
    }

    private static String timestampLiteral(LocalDate day) {
        return "TIMESTAMP '" + day + " 00:00:00'";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String quoteIdent(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
