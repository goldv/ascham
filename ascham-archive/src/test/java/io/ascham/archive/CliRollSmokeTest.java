package io.ascham.archive;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.archive.cli.AschamArchiveCli;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The CLI end to end against a real local warehouse — hermetic, like {@link LocalRollTest}, but
 * entered through the command line. Lives in this package for {@link ArchiveFixtures}.
 */
class CliRollSmokeTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 25);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 26);

    @TempDir
    Path arena;
    @TempDir
    Path warehouse;

    @Test
    void rollsTwoDaysThenRerunsAsANoOp() {
        ArchiveFixtures.writeDays(arena, List.of(D1, D2), 100);

        StringWriter first = new StringWriter();
        assertThat(execute(first)).isZero();
        assertThat(first.toString())
                .contains("rolling from " + arena.toAbsolutePath())
                .contains("ROLLED")
                .contains("total: 200 rows rolled");

        StringWriter rerun = new StringWriter();
        assertThat(execute(rerun)).isZero();
        assertThat(rerun.toString())
                .contains("ALREADY_ROLLED")
                .contains("total: 0 rows rolled");
    }

    private int execute(StringWriter out) {
        CommandLine cmd = new CommandLine(new AschamArchiveCli());
        cmd.setOut(new PrintWriter(out));
        return cmd.execute("roll",
                "--arena-dir", arena.toString(),
                "--dest", warehouse.toString(),
                "--sort", "quotes=sym,ts",
                "--liveness-probe", "150ms");
    }
}
