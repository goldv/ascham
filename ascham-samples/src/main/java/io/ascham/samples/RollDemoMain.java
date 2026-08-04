package io.ascham.samples;

import io.ascham.archive.ArchiveConfig;
import io.ascham.archive.IcebergRollExecutor;
import io.ascham.archive.RollService;
import java.nio.file.Path;

/**
 * Rolls whatever the demo writer has produced into an Iceberg warehouse and reports what moved —
 * the cold tier end to end, on demo data.
 *
 * <pre>
 *   ./gradlew :ascham-samples:backfill --args="--days 3"
 *   ./gradlew :ascham-samples:roll                                      # local warehouse under build/
 *   ./gradlew :ascham-samples:roll --args="--dest http://localhost:8181/catalog"   # dev/ REST stack
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
              --max-segments-per-file N  cap on consecutive segments per rolled parquet file
                                     (default 0 = no cap: one file per roll interval)
            """;

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        DemoArgs options = DemoArgs.parse(args);
        Path dir = options.dir();

        ArchiveConfig config = ArchiveConfig.builder()
                .arenaBaseDir(dir)
                .destination(options.stringValue("dest", "build/warehouse"))
                .warehouseName(options.stringValue("warehouse", "ito"))
                .maxSegmentsPerFile(options.maxSegmentsPerFile())
                // No sortColumns here: DemoSchemas declares ascham.sort_key on (sym, ts), and the
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
                System.out.printf("  %-8s rolled %d interval(s), %,d rows%n",
                        table.table(), table.roll().rolled().size(), table.roll().totalRows());
                table.roll().intervals().forEach(interval ->
                        System.out.printf("      [%s, %s)  %-14s %,d rows%n",
                                interval.start(), interval.end(), interval.status(), interval.rows()));
            }
            System.out.printf("total: %,d rows rolled, %,d bytes still in the arena%n",
                    pass.rowsRolled(), pass.arenaBytes());
            if (!pass.failures().isEmpty()) {
                System.exit(1);
            }
        }
    }
}
