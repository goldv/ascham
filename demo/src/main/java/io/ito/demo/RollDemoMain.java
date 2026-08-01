package io.ito.demo;

import io.ito.cold.ColdConfig;
import io.ito.cold.IcebergRollExecutor;
import io.ito.cold.RollService;
import java.nio.file.Path;

/**
 * Rolls whatever the demo writer has produced into an Iceberg warehouse and reports what moved —
 * the cold tier end to end, on demo data.
 *
 * <pre>
 *   ./gradlew :demo:backfill --args="--days 3"
 *   ./gradlew :demo:roll                                      # local warehouse under build/
 *   ./gradlew :demo:roll --args="--dest http://localhost:8181/catalog"   # dev/ REST stack
 * </pre>
 *
 * <p>Rolling only moves data; nothing is deleted from the arena. Reclamation of archived segments
 * is a separate utility, driven by the provenance each roll commit records.
 */
public final class RollDemoMain {

    private static final String USAGE = """
            Rolls demo data from the arena into an Iceberg warehouse.

              --dir PATH             segment base directory (default /dev/shm/ito, else build/segments)
              --dest PATH|URL        local warehouse path, or Iceberg REST endpoint (http(s)://...)
                                     (default build/warehouse — no services needed)
              --warehouse NAME       REST warehouse name (default ito; unused for local paths)
              --segments-per-file N  consecutive segments per rolled parquet file (default 1)
            """;

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        DemoArgs options = DemoArgs.parse(args);
        Path dir = options.dir();

        ColdConfig config = ColdConfig.builder()
                .arenaBaseDir(dir)
                .destination(options.stringValue("dest", "build/warehouse"))
                .warehouseName(options.stringValue("warehouse", "ito"))
                .segmentsPerFile(options.segmentsPerFile())
                // No sortColumns here: DemoSchemas declares arena.sort_key on (sym, ts), and the
                // roll reads the order straight from the schema.
                .build();

        System.out.printf("rolling from %s into %s%n", dir.toAbsolutePath(), config.destination());
        try (IcebergRollExecutor executor = new IcebergRollExecutor(config)) {
            RollService service = new RollService(config, executor, 0);
            RollService.Pass pass = service.runOnce();

            for (RollService.TableOutcome table : pass.tables()) {
                if (table.failed()) {
                    System.out.printf("  %-8s FAILED: %s%n", table.table(), table.failure().getMessage());
                    continue;
                }
                System.out.printf("  %-8s rolled %d day(s), %,d rows%n",
                        table.table(), table.roll().rolled().size(), table.roll().totalRows());
                table.roll().days().forEach(day ->
                        System.out.printf("      %s  %-14s %,d rows%n", day.day(), day.status(), day.rows()));
            }
            System.out.printf("total: %,d rows rolled, %,d bytes still in the arena%n",
                    pass.rowsRolled(), pass.arenaBytes());
            if (!pass.failures().isEmpty()) {
                System.exit(1);
            }
        }
    }
}
