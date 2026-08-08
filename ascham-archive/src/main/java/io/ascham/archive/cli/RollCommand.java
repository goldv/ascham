package io.ascham.archive.cli;

import io.ascham.archive.ArchiveConfig;
import io.ascham.archive.ArchiveException;
import io.ascham.archive.IcebergRollExecutor;
import io.ascham.archive.RollExecutor;
import io.ascham.archive.RollService;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * One roll pass over every table in the arena: discover what is complete, copy it into the
 * historical store, report what moved. Pull-based and idempotent — a failed or missed run recovers
 * by simply running again, so there is no state to manage between invocations.
 */
@Command(name = "roll",
        mixinStandardHelpOptions = true,
        description = "Roll completed arena intervals into an Iceberg warehouse. Rolling only "
                + "copies data; nothing is deleted from the arena (reclamation is a separate, "
                + "future command). Safe to re-run: already-rolled intervals are skipped. Output "
                + "is human-oriented and not a stable format for parsing.",
        footer = {
                "",
                "Exit codes:",
                "  0   every table rolled (or had nothing to do)",
                "  1   at least one table failed, or the catalog could not be opened",
                "  2   usage error",
        })
final class RollCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-a", "--arena-dir"}, required = true, paramLabel = "PATH",
            converter = CliConverters.TildePath.class,
            description = "Arena segment base directory; every table directory under it holding "
                    + "segments is rolled. A leading ~ is expanded (the shell cannot expand it "
                    + "inside --args=\"...\").")
    Path arenaDir;

    @Option(names = {"-d", "--dest"}, required = true, paramLabel = "PATH|URL",
            converter = CliConverters.TildeString.class,
            description = "Where the historical data goes. A local path or file:// URI rolls to "
                    + "disk through a serverless Hadoop catalog; an http(s):// URI is an Iceberg "
                    + "REST catalog endpoint (object storage comes from the catalog's warehouse). "
                    + "A bare s3:// warehouse is rejected: Hadoop-catalog commits are not atomic "
                    + "on object stores. A leading ~ is expanded; a relative path lands under the "
                    + "Gradle project directory when run via the gradle task.")
    String dest;

    @Option(names = "--warehouse", defaultValue = "ito", paramLabel = "NAME",
            description = "Warehouse name sent to a REST catalog (default: ${DEFAULT-VALUE}); "
                    + "unused for local destinations.")
    String warehouse;

    @Option(names = "--namespace", defaultValue = "ito", paramLabel = "NAME",
            description = "Namespace holding the rolled tables (default: ${DEFAULT-VALUE}).")
    String namespace;

    @Option(names = "--sort", paramLabel = "TABLE=COL[,COL...]",
            description = "Sort order for a table's rolled files, repeatable per table (e.g. "
                    + "--sort quotes=sym,ts). Tables without an entry fall back to the schema's "
                    + "own ascham.sort_key declaration, then to the arena time column alone.")
    Map<String, String> sort = new LinkedHashMap<>();

    @Option(names = "--catalog-property", paramLabel = "KEY=VALUE",
            description = "Extra Iceberg catalog property, repeatable; applied last, so it "
                    + "overrides anything the typed options set. The escape hatch for whatever "
                    + "this surface does not cover.")
    Map<String, String> catalogProperties = new LinkedHashMap<>();

    @Option(names = "--max-segments-per-file", defaultValue = "0", paramLabel = "N",
            description = "Cap on consecutive segments per rolled parquet file (default: "
                    + "${DEFAULT-VALUE} = no cap, one file per roll interval). Set it when "
                    + "capacity rotation packs more segments into an interval than one parquet "
                    + "file should hold.")
    int maxSegmentsPerFile;

    @Option(names = "--target-file-size", defaultValue = "512m", paramLabel = "SIZE",
            converter = CliConverters.ByteSize.class,
            description = "Written as write.target-file-size-bytes on created tables (default: "
                    + "${DEFAULT-VALUE}). Advisory for engines that compact later; the roll's "
                    + "actual file size comes from the roll cycle and --max-segments-per-file. "
                    + "Accepts plain bytes or k/m/g/t suffixes (binary multiples).")
    long targetFileSizeBytes;

    @Option(names = "--liveness-probe", defaultValue = "5s", paramLabel = "DURATION",
            converter = CliConverters.HumanDuration.class,
            description = "How long to watch a heartbeat before concluding a writer is gone "
                    + "(default: ${DEFAULT-VALUE}). Accepts 500ms/5s/2m/1h/1d or ISO-8601.")
    Duration livenessProbe;

    @Option(names = "--arena-alert-bytes", defaultValue = "0", paramLabel = "SIZE",
            converter = CliConverters.ByteSize.class,
            description = "Log an ERROR when the arena still holds more than SIZE bytes after the "
                    + "pass — the only warning before shared memory fills when rolls stop keeping "
                    + "up (default: ${DEFAULT-VALUE} = disabled). Accepts k/m/g/t suffixes.")
    long arenaAlertBytes;

    @Option(names = {"-q", "--quiet"},
            description = "Only failures and the final totals; per-interval detail is suppressed.")
    boolean quiet;

    /** validate = false: purely a help-section grouping, the options behave as plain options —
     *  group matching semantics would swallow the env-var defaults. All-or-none is enforced in
     *  {@link #s3Credentials()} instead. */
    @ArgGroup(validate = false, heading = "%nS3 object-store credentials (usually unnecessary: a "
            + "REST catalog vends scoped credentials — see dev/README.md):%n")
    S3Options s3 = new S3Options();

    static final class S3Options {
        @Option(names = "--s3-endpoint", defaultValue = "${env:ITO_S3_ENDPOINT}", paramLabel = "URL",
                description = "S3-compatible endpoint, e.g. http://localhost:9100 "
                        + "(default: $ITO_S3_ENDPOINT).")
        String endpoint;

        @Option(names = "--s3-key-id", defaultValue = "${env:ITO_S3_KEY_ID}", paramLabel = "ID",
                description = "Access key id (default: $ITO_S3_KEY_ID).")
        String keyId;

        @Option(names = "--s3-secret", defaultValue = "${env:ITO_S3_SECRET}", paramLabel = "SECRET",
                interactive = true, arity = "0..1",
                description = "Secret access key. Prefer $ITO_S3_SECRET, or pass a bare "
                        + "--s3-secret to be prompted without echo — an inline value is visible "
                        + "to every user on the host via ps.")
        String secret;

        // Boxed with no default on purpose: a `defaultValue = "true"` negatable flag inverts in
        // picocli (the plain form yields false), while an unset Boolean keeps both forms literal.
        @Option(names = "--s3-path-style", negatable = true,
                description = "Path-style object addressing (default: on, the MinIO style; AWS S3 "
                        + "wants --no-s3-path-style).")
        Boolean pathStyle;
    }

    private final Function<ArchiveConfig, RollExecutor> executorFactory;

    RollCommand() {
        this(IcebergRollExecutor::new);
    }

    /** Test seam: run the pass against a fake store instead of a real Iceberg catalog. */
    RollCommand(Function<ArchiveConfig, RollExecutor> executorFactory) {
        this.executorFactory = executorFactory;
    }

    @Override
    public Integer call() {
        ArchiveConfig config = toConfig();
        PrintWriter out = spec.commandLine().getOut();
        out.printf("rolling from %s into %s%n", arenaDir.toAbsolutePath(), config.destination());
        try (RollExecutor executor = executorFactory.apply(config)) {
            RollService.Pass pass = new RollService(config, executor, arenaAlertBytes).runOnce();
            report(pass, out);
            out.flush();
            return pass.failures().isEmpty() ? 0 : 1;
        } catch (ArchiveException e) {
            // The known config-error path (bad destination, foreign arena, ...): the message says
            // it all, a stack trace would just bury it.
            PrintWriter err = spec.commandLine().getErr();
            err.println("error: " + e.getMessage());
            err.flush();
            return 1;
        }
    }

    /** Pure translation of the parsed options into the library config; no I/O. */
    ArchiveConfig toConfig() {
        ArchiveConfig.Builder builder = ArchiveConfig.builder()
                .arenaBaseDir(arenaDir)
                .destination(dest)
                .warehouseName(warehouse)
                .namespace(namespace)
                .maxSegmentsPerFile(maxSegmentsPerFile)
                .targetFileSizeBytes(targetFileSizeBytes)
                .livenessProbe(livenessProbe);
        if (!sort.isEmpty()) {
            builder.sortColumns(sortColumns());
        }
        if (!catalogProperties.isEmpty()) {
            builder.catalogProperties(catalogProperties);
        }
        ArchiveConfig.S3Credentials credentials = s3Credentials();
        if (credentials != null) {
            builder.s3(credentials);
        }
        return builder.build();
    }

    private Map<String, List<String>> sortColumns() {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        sort.forEach((table, colSpec) -> {
            List<String> names = new ArrayList<>();
            for (String column : colSpec.split(",", -1)) {
                if (column.isBlank()) {
                    throw new ParameterException(spec.commandLine(),
                            "Invalid value for --sort: empty column name in '" + table + "=" + colSpec + "'");
                }
                names.add(column.strip());
            }
            columns.put(table, List.copyOf(names));
        });
        return columns;
    }

    private ArchiveConfig.S3Credentials s3Credentials() {
        boolean any = s3.endpoint != null || s3.keyId != null || s3.secret != null;
        if (!any) {
            return null;
        }
        if (s3.endpoint == null || s3.keyId == null || s3.secret == null) {
            throw new ParameterException(spec.commandLine(),
                    "--s3-endpoint, --s3-key-id and --s3-secret must be given together "
                            + "(directly or via ITO_S3_ENDPOINT/ITO_S3_KEY_ID/ITO_S3_SECRET)");
        }
        return new ArchiveConfig.S3Credentials(s3.endpoint, s3.keyId, s3.secret,
                s3.endpoint.startsWith("https://"), s3.pathStyle == null || s3.pathStyle);
    }

    private void report(RollService.Pass pass, PrintWriter out) {
        for (RollService.TableOutcome table : pass.tables()) {
            if (table.failed()) {
                out.printf("  %-8s FAILED: %s%n", table.table(), table.failure().getMessage());
                continue;
            }
            if (quiet) {
                continue;
            }
            out.printf("  %-8s rolled %d interval(s), %,d rows%n",
                    table.table(), table.roll().rolled().size(), table.roll().totalRows());
            table.roll().intervals().forEach(interval ->
                    out.printf("      [%s, %s)  %-14s %,d rows%n",
                            interval.start(), interval.end(), interval.status(), interval.rows()));
        }
        out.printf("total: %,d rows rolled, %,d bytes still in the arena%n",
                pass.rowsRolled(), pass.arenaBytes());
    }
}
