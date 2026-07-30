package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.rotate.DailyRotationPolicy;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The R4 exit tests: a real roll into the local Iceberg catalog, and the recovery branches that make
 * it idempotent (docs/cold-tier-design-plan.md §3.3, §11).
 *
 * <p>Requires the dev stack and the built arena extension:
 * <pre>
 *   docker compose -f dev/docker-compose.yml up -d
 *   DUCKDB=/path/to/duckdb arena-duckdb/scripts/build_extension.sh
 *   ./gradlew :cold:rollIT
 * </pre>
 *
 * <p>Each test gets its own catalog namespace, so runs are independent and re-runnable.
 */
@Tag("catalog")
class RollIT {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 28);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 29);

    @TempDir
    Path arenaBase;

    private String namespace;
    private ColdConfig config;
    private DuckDbRollExecutor executor;

    @BeforeEach
    void setUp() {
        Path extension = Path.of(System.getProperty("io.ito.cold.arenaExtension",
                "arena-duckdb/build/arena.duckdb_extension"));
        assertThat(Files.exists(extension))
                .withFailMessage("arena extension not built at %s — run arena-duckdb/scripts/build_extension.sh",
                        extension)
                .isTrue();

        namespace = "it_" + Long.toHexString(System.nanoTime());
        config = ColdConfig.builder()
                .arenaBaseDir(arenaBase)
                .arenaExtension(extension)
                .catalog("http://localhost:8181/catalog", "ito")
                .namespace(namespace)
                .metaNamespace(namespace + "_meta")
                .sortColumns(java.util.Map.of("quotes", List.of("sym", "ts")))
                .memoryLimit("256MB")
                .livenessProbe(java.time.Duration.ofMillis(200))
                .build();
        executor = new DuckDbRollExecutor(config);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void rollsTwoDaysWithRowParitySortednessAndAWatermark() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, D2, TODAY), 500);
        TableRoller roller = new TableRoller(config, executor);

        TableRoller.RollResult result = roller.roll("quotes", TODAY);

        // Both completed days rolled; today is left alone because the writer still owns it.
        assertThat(result.days()).extracting(TableRoller.DayResult::day).containsExactly(D1, D2);
        assertThat(result.days()).allMatch(d -> d.status() == TableRoller.DayStatus.ROLLED);
        assertThat(result.totalRows()).isEqualTo(1000);

        // Row parity per day, and nothing from today leaked in.
        assertThat(rowsInDay(D1)).isEqualTo(500);
        assertThat(rowsInDay(D2)).isEqualTo(500);
        assertThat(rowsInDay(TODAY)).isZero();

        // Files are physically sorted by (sym, ts) — what gives Parquet stats their pruning shape.
        assertThat(isSortedBySymThenTs()).isTrue();

        // The watermark advanced to the last rolled day, so the cutover is the day after.
        assertThat(executor.highestRolledDay("quotes")).contains(D2);
        assertThat(roller.cutoverDay("quotes")).contains(TODAY);

        // The roll log records exactly which segment files each day came from.
        assertThat(rollLogSegments(D1)).isNotEmpty().allMatch(s -> s.startsWith("20260727."));
        assertThat(rollLogSegments(D2)).isNotEmpty().allMatch(s -> s.startsWith("20260728."));
    }

    @Test
    void nanosecondTimestampsSurviveTheRoll() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, TODAY), 200);
        new TableRoller(config, executor).roll("quotes", TODAY);

        // The fixture writes sub-microsecond digits; they must still be there after the round trip,
        // or the historical tier would silently disagree with the live one.
        assertThat(query("SELECT count(*) FROM " + config.qualified("quotes")
                + " WHERE epoch_ns(ts) % 1000 <> 0")).isGreaterThan(0L);
        long arenaMax = query("SELECT max(epoch_ns(ts)) FROM arena_scan('"
                + arenaBase.resolve("quotes").toAbsolutePath() + "') WHERE ts < TIMESTAMP '" + D1.plusDays(1) + "'");
        long icebergMax = query("SELECT max(epoch_ns(ts)) FROM " + config.qualified("quotes"));
        assertThat(icebergMax).isEqualTo(arenaMax);
    }

    @Test
    void rerunningIsANoOpRatherThanADuplicate() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, D2, TODAY), 300);
        TableRoller roller = new TableRoller(config, executor);

        roller.roll("quotes", TODAY);
        TableRoller.RollResult second = roller.roll("quotes", TODAY);

        assertThat(second.days()).allMatch(d -> d.status() == TableRoller.DayStatus.ALREADY_ROLLED);
        assertThat(rowsInDay(D1)).isEqualTo(300);
        assertThat(rowsInDay(D2)).isEqualTo(300);
        assertThat(duplicateTimestampCount()).isZero();
    }

    @Test
    void aRollThatDiedBetweenTheDataAndLogCommitsIsRepairedNotRepeated() {
        ColdFixtures.writeDays(arenaBase, List.of(D1, TODAY), 400);
        TableRoller roller = new TableRoller(config, executor);

        // Reproduce the crash window exactly: the day's data is committed, but the roll-log entry
        // never made it. (A real kill -9 mid-COMMIT lands here; we construct the same state so the
        // assertion is deterministic.)
        executor.ensureTable("quotes", ColdFixtures.quotesSchema(64), List.of("sym", "ts"));
        exec("INSERT INTO " + config.qualified("quotes") + " SELECT ts, sym, px FROM arena_scan('"
                + arenaBase.resolve("quotes").toAbsolutePath() + "') WHERE ts >= TIMESTAMP '" + D1
                + "' AND ts < TIMESTAMP '" + D1.plusDays(1) + "'");
        assertThat(rowsInDay(D1)).isEqualTo(400);
        assertThat(executor.rolledBy("quotes", D1)).isEmpty();

        TableRoller.RollResult result = roller.roll("quotes", TODAY);

        // The day is recognised as already committed: the log is repaired, the data is not re-copied.
        assertThat(result.days()).singleElement()
                .satisfies(d -> {
                    assertThat(d.day()).isEqualTo(D1);
                    assertThat(d.status()).isEqualTo(TableRoller.DayStatus.LOG_REPAIRED);
                    assertThat(d.rows()).isEqualTo(400);
                });
        assertThat(rowsInDay(D1)).isEqualTo(400); // not 800
        assertThat(duplicateTimestampCount()).isZero();
        assertThat(executor.highestRolledDay("quotes")).contains(D1);
    }

    @Test
    void aDayStillOwnedByALiveWriterIsLeftForTheNextRun() throws Exception {
        // One past day, still the newest segment, with a writer actively heartbeating on it.
        SegmentDirectory dir = new SegmentDirectory(arenaBase, "quotes");
        long d1Start = D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(),
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant()),
                ColdFixtures.counterNanoClock())) {
            writer.append(ColdFixtures.row(d1Start + 5, "AAPL", 1));

            Thread beating = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    writer.heartbeat();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            beating.start();
            try {
                TableRoller.RollResult result = new TableRoller(config, executor).roll("quotes", TODAY);
                assertThat(result.days()).singleElement()
                        .extracting(TableRoller.DayResult::status)
                        .isEqualTo(TableRoller.DayStatus.NOT_FROZEN);
                assertThat(executor.highestRolledDay("quotes")).isEmpty(); // watermark did not move
            } finally {
                beating.interrupt();
                beating.join();
            }
        }
    }

    @Test
    void aMisalignedDayAbortsInsteadOfSplitting() {
        SegmentDirectory dir = new SegmentDirectory(arenaBase, "quotes");
        long d1Start = D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(),
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant()),
                ColdFixtures.counterNanoClock())) {
            writer.append(ColdFixtures.row(d1Start + 5, "AAPL", 1));
            writer.append(ColdFixtures.row( // a row whose event time belongs to the next day
                    D2.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L + 9, "MSFT", 2));
        }
        // Make D1 provably frozen by giving the directory a newer day.
        ColdFixtures.writeDays(arenaBase, List.of(D2), 5);

        assertThatThrownBy(() -> new TableRoller(config, executor).roll("quotes", TODAY))
                .isInstanceOf(ArenaInventory.DayAlignmentException.class);
        // Nothing was written: the day is refused whole, never partially archived.
        assertThat(executor.highestRolledDay("quotes")).isEmpty();
    }

    @Test
    void aDayLargerThanTheMemoryLimitStillSorts() {
        // memory_limit is 256MB for this session; 150k rows forces the day sort to spill to disk
        // rather than fail, which is what makes an EOD roll of a real trading day survivable.
        ColdFixtures.writeDays(arenaBase, List.of(D1, TODAY), 150_000);

        TableRoller.RollResult result = new TableRoller(config, executor).roll("quotes", TODAY);

        assertThat(result.totalRows()).isEqualTo(150_000);
        assertThat(rowsInDay(D1)).isEqualTo(150_000);
        assertThat(isSortedBySymThenTs()).isTrue();
    }

    @Test
    void anUnknownTableIsRejectedRatherThanInvented() {
        assertThatThrownBy(() -> new TableRoller(config, executor).roll("nope", TODAY))
                .isInstanceOf(ColdException.class)
                .hasMessageContaining("no arena table directory");
        // And the typo did not leave a stray directory behind.
        assertThat(Files.exists(arenaBase.resolve("nope"))).isFalse();
    }

    // --- helpers ---

    private long rowsInDay(LocalDate day) {
        return query("SELECT count(*) FROM " + config.qualified("quotes")
                + " WHERE ts >= TIMESTAMP '" + day + "' AND ts < TIMESTAMP '" + day.plusDays(1) + "'");
    }

    /** Rows sharing a (ts, sym) pair — non-zero means the roll duplicated data. */
    private long duplicateTimestampCount() {
        return query("SELECT coalesce(sum(n - 1), 0) FROM (SELECT count(*) n FROM "
                + config.qualified("quotes") + " GROUP BY ts, sym HAVING count(*) > 1)");
    }

    /**
     * Whether rows are physically ordered by (sym, ts) <em>within each day partition</em> — the
     * layout the roll's ORDER BY exists to produce, and what makes Parquet row-group stats tight
     * enough for symbol pruning. Sortedness is per-partition, not table-wide: each day is a separate
     * file, so reading several days concatenates several independently sorted runs.
     *
     * <p>Scan order is materialised with {@code row_number() OVER ()}; an Iceberg scan has no
     * {@code rowid}.
     */
    private boolean isSortedBySymThenTs() {
        return query("SELECT count(*) FROM ("
                + "  SELECT sym, ts,"
                + "    lag(sym) OVER (PARTITION BY ts::DATE ORDER BY rn) prev_sym,"
                + "    lag(ts)  OVER (PARTITION BY ts::DATE ORDER BY rn) prev_ts"
                + "  FROM (SELECT sym, ts, row_number() OVER () AS rn FROM " + config.qualified("quotes") + ")"
                + ") WHERE prev_sym IS NOT NULL AND (sym < prev_sym OR (sym = prev_sym AND ts < prev_ts))") == 0;
    }

    private List<String> rollLogSegments(LocalDate day) {
        List<String> out = new ArrayList<>();
        try (Statement st = executor.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT segments FROM " + config.qualifiedRollLog()
                     + " WHERE table_name = 'quotes' AND day = DATE '" + day + "'")) {
            while (rs.next()) {
                for (String name : rs.getString(1).split(",")) {
                    out.add(name);
                }
            }
        } catch (Exception e) {
            throw new AssertionError("failed reading the roll log", e);
        }
        return out;
    }

    private long query(String sql) {
        try (Statement st = executor.connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Exception e) {
            throw new AssertionError("query failed: " + sql, e);
        }
    }

    private void exec(String sql) {
        try (Statement st = executor.connection().createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new AssertionError("exec failed: " + sql, e);
        }
    }

}
