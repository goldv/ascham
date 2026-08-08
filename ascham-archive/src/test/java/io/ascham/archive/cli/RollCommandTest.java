package io.ascham.archive.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import io.ascham.archive.ArchiveConfig;
import io.ascham.archive.ArchiveException;
import io.ascham.archive.RollExecutor;
import io.ascham.schema.ArenaSchema;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.TypeConversionException;

/** Option parsing, config translation, and exit codes — no warehouse, no arena data. */
class RollCommandTest {

    @TempDir
    Path arena;
    @TempDir
    Path warehouse;

    @Test
    void minimalArgsUseTheLibraryDefaults() {
        RollCommand roll = parse("roll", "-a", arena.toString(), "-d", warehouse.toString());
        ArchiveConfig config = roll.toConfig();

        assertThat(config.arenaBaseDir()).isEqualTo(arena);
        assertThat(config.destination()).isEqualTo(warehouse.toString());
        assertThat(config.warehouseName()).isEqualTo("ito");
        assertThat(config.namespace()).isEqualTo("ito");
        assertThat(config.maxSegmentsPerFile()).isZero();
        assertThat(config.targetFileSizeBytes()).isEqualTo(512L << 20);
        assertThat(config.livenessProbe()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.catalogProperties()).isEmpty();
    }

    @Test
    void everyKnobReachesTheConfig() {
        RollCommand roll = parse("roll",
                "--arena-dir", arena.toString(),
                "--dest", "http://localhost:8181/catalog",
                "--warehouse", "prod",
                "--namespace", "md",
                "--sort", "quotes=sym,ts",
                "--sort", "trades=ts",
                "--catalog-property", "clients=4",
                "--max-segments-per-file", "2",
                "--target-file-size", "256m",
                "--liveness-probe", "2s");
        ArchiveConfig config = roll.toConfig();

        assertThat(config.destination()).isEqualTo("http://localhost:8181/catalog");
        assertThat(config.warehouseName()).isEqualTo("prod");
        assertThat(config.namespace()).isEqualTo("md");
        assertThat(config.sortColumnsFor("quotes", null)).containsExactly("sym", "ts");
        assertThat(config.sortColumnsFor("trades", null)).containsExactly("ts");
        assertThat(config.catalogProperties()).containsEntry("clients", "4");
        assertThat(config.maxSegmentsPerFile()).isEqualTo(2);
        assertThat(config.targetFileSizeBytes()).isEqualTo(256L << 20);
        assertThat(config.livenessProbe()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aLeadingTildeExpandsToTheHomeDirectory() {
        String home = System.getProperty("user.home");
        RollCommand roll = parse("roll", "-a", "~/segments", "-d", "~/warehouse");
        ArchiveConfig config = roll.toConfig();

        assertThat(config.arenaBaseDir()).isEqualTo(Path.of(home, "segments"));
        assertThat(config.destination()).isEqualTo(home + "/warehouse");
    }

    @Test
    void urlDestinationsAreNotTildeExpanded() {
        RollCommand roll = parse("roll", "-a", arena.toString(),
                "-d", "http://localhost:8181/catalog");
        assertThat(roll.toConfig().destination()).isEqualTo("http://localhost:8181/catalog");
    }

    @Test
    void aTildeUserPathIsAUsageError() {
        assertThat(execute(new StringWriter(), "roll", "-a", arena.toString(),
                "-d", "~goldv/warehouse")).isEqualTo(2);
    }

    @Test
    void s3CredentialsRequireAllThreeParts() {
        assumeThat(System.getenv("ITO_S3_KEY_ID")).isNull();
        assumeThat(System.getenv("ITO_S3_SECRET")).isNull();

        RollCommand roll = parse("roll", "-a", arena.toString(), "-d", warehouse.toString(),
                "--s3-endpoint", "http://localhost:9100");

        assertThatThrownBy(roll::toConfig)
                .isInstanceOf(ParameterException.class)
                .hasMessageContaining("--s3-key-id");
    }

    @Test
    void completeS3CredentialsCarryThrough() {
        RollCommand roll = parse("roll", "-a", arena.toString(), "-d", warehouse.toString(),
                "--s3-endpoint", "https://s3.example.com",
                "--s3-key-id", "key",
                "--s3-secret", "secret",
                "--no-s3-path-style");
        ArchiveConfig.S3Credentials s3 = roll.toConfig().s3();

        assertThat(s3.endpoint()).isEqualTo("https://s3.example.com");
        assertThat(s3.keyId()).isEqualTo("key");
        assertThat(s3.secret()).isEqualTo("secret");
        assertThat(s3.useSsl()).isTrue();
        assertThat(s3.pathStyle()).isFalse();
    }

    @Test
    void missingRequiredOptionsAreUsageErrors() {
        assertThat(execute(new StringWriter(), "roll")).isEqualTo(2);
        assertThat(execute(new StringWriter(), "roll", "-a", arena.toString())).isEqualTo(2);
    }

    @Test
    void noSubcommandIsAUsageError() {
        assertThat(execute(new StringWriter())).isEqualTo(2);
    }

    @Test
    void malformedSizesAndDurationsAreUsageErrors() {
        assertThat(execute(new StringWriter(), "roll", "-a", arena.toString(),
                "-d", warehouse.toString(), "--target-file-size", "big")).isEqualTo(2);
        assertThat(execute(new StringWriter(), "roll", "-a", arena.toString(),
                "-d", warehouse.toString(), "--liveness-probe", "soon")).isEqualTo(2);
    }

    @Test
    void anEmptySortColumnIsAUsageError() {
        RollCommand roll = parse("roll", "-a", arena.toString(), "-d", warehouse.toString(),
                "--sort", "quotes=sym,,ts");
        assertThatThrownBy(roll::toConfig)
                .isInstanceOf(ParameterException.class)
                .hasMessageContaining("empty column name");
    }

    @Test
    void anEmptyArenaRollsNothingAndExitsZero() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RollCommand(config -> new NoopExecutor()));
        cmd.setOut(new PrintWriter(out));

        int code = cmd.execute("-a", arena.toString(), "-d", warehouse.toString());

        assertThat(code).isZero();
        assertThat(out.toString()).contains("total: 0 rows rolled");
    }

    @Test
    void anArchiveExceptionIsReportedAsOneLineAndExitsOne() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new RollCommand(config -> {
            throw new ArchiveException("catalog unreachable (injected)");
        }));
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(err));

        int code = cmd.execute("-a", arena.toString(), "-d", warehouse.toString());

        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).contains("error: catalog unreachable (injected)");
    }

    @Test
    void byteSizesParseAsBinaryMultiples() {
        CliConverters.ByteSize sizes = new CliConverters.ByteSize();
        assertThat(sizes.convert("1048576")).isEqualTo(1L << 20);
        assertThat(sizes.convert("512m")).isEqualTo(512L << 20);
        assertThat(sizes.convert("1G")).isEqualTo(1L << 30);
        assertThat(sizes.convert("2kb")).isEqualTo(2L << 10);
        assertThat(sizes.convert("1t")).isEqualTo(1L << 40);
        assertThat(sizes.convert("0")).isZero();
        for (String bad : new String[] {"", "big", "-1", "1.5g", "1x", "9999999999g"}) {
            assertThatThrownBy(() -> sizes.convert(bad))
                    .as("size '%s'", bad)
                    .isInstanceOf(TypeConversionException.class);
        }
    }

    @Test
    void durationsParseHumanAndIsoForms() {
        CliConverters.HumanDuration durations = new CliConverters.HumanDuration();
        assertThat(durations.convert("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(durations.convert("5s")).isEqualTo(Duration.ofSeconds(5));
        assertThat(durations.convert("2m")).isEqualTo(Duration.ofMinutes(2));
        assertThat(durations.convert("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(durations.convert("1d")).isEqualTo(Duration.ofDays(1));
        assertThat(durations.convert("PT30S")).isEqualTo(Duration.ofSeconds(30));
        for (String bad : new String[] {"", "soon", "5", "5 s", "P"}) {
            assertThatThrownBy(() -> durations.convert(bad))
                    .as("duration '%s'", bad)
                    .isInstanceOf(TypeConversionException.class);
        }
    }

    // --- helpers ---

    private static RollCommand parse(String... args) {
        ParseResult result = new CommandLine(new AschamArchiveCli()).parseArgs(args);
        return (RollCommand) result.subcommand().commandSpec().userObject();
    }

    private static int execute(StringWriter err, String... args) {
        CommandLine cmd = new CommandLine(new AschamArchiveCli());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    /** Never asked to do anything in these tests — the arena is empty. */
    private static final class NoopExecutor implements RollExecutor {
        @Override
        public void ensureTable(String table, ArenaSchema schema, List<String> sortColumns) {
        }

        @Override
        public Optional<Instant> rolledThrough(String table) {
            return Optional.empty();
        }

        @Override
        public long rollInterval(String table, ArenaSchema schema, Instant intervalStart,
                                 Instant intervalEnd, List<Path> segments, List<String> sortColumns) {
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
