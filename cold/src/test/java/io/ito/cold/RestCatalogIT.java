package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The REST-catalog smoke test: the native roll against the dev stack (Lakekeeper + MinIO), read
 * back through DuckDB's iceberg extension. LocalRollTest is the correctness suite; this pins the
 * two integration seams it cannot — committing through a real REST catalog onto object storage,
 * and the dev query surface being able to read what the native writer wrote (v3, timestamp_ns,
 * zstd).
 *
 * <p>Requires the dev stack, nothing else (no arena extension):
 * <pre>
 *   docker compose -f dev/docker-compose.yml up -d
 *   ./gradlew :cold:rollIT
 * </pre>
 *
 * <p>Each run gets its own catalog namespace, so runs are independent and re-runnable.
 */
@Tag("catalog")
class RestCatalogIT {

    private static final String CATALOG_ENDPOINT = "http://localhost:8181/catalog";
    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 29);
    private static final long NANOS_PER_DAY = 86_400L * 1_000_000_000L;
    private static final int ROWS = 500;

    @TempDir
    Path arenaBase;

    private String namespace;
    private ColdConfig config;
    private IcebergRollExecutor executor;

    @BeforeEach
    void setUp() {
        namespace = "it_" + Long.toHexString(System.nanoTime());
        config = ColdConfig.builder()
                .arenaBaseDir(arenaBase)
                .destination(CATALOG_ENDPOINT)
                .warehouseName("ito")
                .namespace(namespace)
                .sortColumns(Map.of("quotes", List.of("sym", "ts")))
                .livenessProbe(java.time.Duration.ofMillis(200))
                .build();
        executor = new IcebergRollExecutor(config);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void nativeRollLandsInLakekeeperAndDuckDbReadsItBack() throws Exception {
        ColdFixtures.writeDays(arenaBase, List.of(D1), ROWS);

        TableRoller.RollResult result = new TableRoller(config, executor).roll("quotes", TODAY);
        assertThat(result.totalRows()).isEqualTo(ROWS);
        assertThat(result.days()).singleElement()
                .extracting(TableRoller.DayResult::status).isEqualTo(TableRoller.DayStatus.ROLLED);

        // The highest nano the fixture wrote — sub-microsecond digits included. If any precision
        // were lost between the native writer and DuckDB's reader, this exact value would not match.
        long dayStart = D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
        long step = NANOS_PER_DAY / (ROWS + 1L);
        long expectedMaxNanos = dayStart + (ROWS - 1) * step + ((ROWS - 1) % 997) + 7;

        try (Connection duckdb = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = duckdb.createStatement()) {
            st.execute("INSTALL iceberg");
            st.execute("INSTALL httpfs");
            st.execute("LOAD httpfs");
            st.execute("LOAD iceberg");
            // dev/hist-attach.sql, minus the schema bootstrap: every clause is load-bearing.
            String s3Endpoint = System.getenv().getOrDefault("ITO_S3_ENDPOINT", "172.17.0.1:9100");
            st.execute("CREATE OR REPLACE SECRET minio (TYPE S3, KEY_ID 'minioadmin', "
                    + "SECRET 'minioadmin', ENDPOINT '" + s3Endpoint + "', URL_STYLE 'path', "
                    + "USE_SSL false)");
            st.execute("ATTACH 'ito' AS hist (TYPE iceberg, ENDPOINT '" + CATALOG_ENDPOINT
                    + "', AUTHORIZATION_TYPE 'none')");

            try (ResultSet rs = st.executeQuery("SELECT count(*), max(epoch_ns(ts)) FROM hist."
                    + namespace + ".quotes")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(ROWS);
                assertThat(rs.getLong(2)).isEqualTo(expectedMaxNanos);
            }
        }
    }
}
