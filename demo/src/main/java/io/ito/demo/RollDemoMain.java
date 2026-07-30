package io.ito.demo;

import io.ito.cold.ColdConfig;
import io.ito.cold.DuckDbRollExecutor;
import io.ito.cold.RollService;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Rolls whatever the demo writer has produced into the Iceberg catalog and reports what moved —
 * the cold tier end to end, on demo data.
 *
 * <pre>
 *   docker compose -f dev/docker-compose.yml up -d
 *   ./gradlew :demo:backfill --args="--days 3"
 *   ./gradlew :demo:roll
 * </pre>
 *
 * <p>Runs with a zero grace period so one invocation completes the whole lifecycle — roll, then
 * reclaim — instead of leaving segments for a later pass. Production uses a real grace window
 * (default 15 minutes) so that in-flight queries whose cutover is still cached keep working.
 */
public final class RollDemoMain {

    private static final String USAGE = """
            Rolls demo data from the arena into the Iceberg catalog.

              --dir PATH        segment base directory (default /dev/shm/ito, else build/segments)
              --extension PATH  arena DuckDB extension (default arena-duckdb/build/arena.duckdb_extension)
              --catalog URL     Iceberg REST endpoint (default http://localhost:8181/catalog)
              --warehouse NAME  warehouse name (default ito)
              --grace-seconds N hold archived segments this long before reclaiming (default 0)
            """;

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        DemoArgs options = DemoArgs.parse(args);
        Path dir = options.dir();
        Path extension = Path.of(System.getProperty("io.ito.demo.arenaExtension",
                "arena-duckdb/build/arena.duckdb_extension"));

        ColdConfig config = ColdConfig.builder()
                .arenaBaseDir(dir)
                .arenaExtension(extension)
                .catalog("http://localhost:8181/catalog", "ito")
                // The sort order travels with the data: DemoSchemas declares arena.sort_key on
                // (sym, ts), so the roll writes files in that order without separate configuration.
                .sortColumns(java.util.Map.of(
                        "quotes", DemoSchemas.sortColumns(DemoSchemas.quotes(4096)),
                        "trades", DemoSchemas.sortColumns(DemoSchemas.trades(4096))))
                .build();

        System.out.printf("rolling from %s into the Iceberg catalog%n", dir.toAbsolutePath());
        try (DuckDbRollExecutor executor = new DuckDbRollExecutor(config)) {
            RollService service = new RollService(config, executor, Duration.ZERO, 0);
            RollService.Pass pass = service.runOnce();

            for (RollService.TableOutcome table : pass.tables()) {
                if (table.failed()) {
                    System.out.printf("  %-8s FAILED: %s%n", table.table(), table.failure().getMessage());
                    continue;
                }
                System.out.printf("  %-8s rolled %d day(s), %,d rows; reclaimed %d segment(s)%n",
                        table.table(), table.roll().rolled().size(), table.roll().totalRows(),
                        table.reclaim().unlinked().size());
                table.roll().days().forEach(day ->
                        System.out.printf("      %s  %-14s %,d rows%n", day.day(), day.status(), day.rows()));
            }
            System.out.printf("total: %,d rows rolled, %d segment(s) reclaimed, %,d bytes still in the arena%n",
                    pass.rowsRolled(), pass.segmentsReclaimed(), pass.arenaBytes());
            if (!pass.failures().isEmpty()) {
                System.exit(1);
            }
        }
    }
}
