package io.ascham.archive.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/**
 * The cold-tier command line: everything that moves arena data into and around the historical
 * store. {@code roll} is the first subcommand; reclamation and cutover are the anticipated next
 * ones (docs/cold-tier-design-plan.md).
 *
 * <p>The gradle wrapper task carries the JVM flags the arena requires:
 * <pre>
 *   ./gradlew :ascham-archive:archive --args="roll --arena-dir /dev/shm/ito --dest build/warehouse"
 * </pre>
 * Anyone launching {@code java -cp} directly must pass the same {@code --add-exports/--add-opens}
 * flags (see ascham-archive/build.gradle.kts) or segment mapping fails at startup.
 */
@Command(name = "ascham-archive",
        mixinStandardHelpOptions = true,
        version = "ascham-archive (development)",
        synopsisSubcommandLabel = "COMMAND",
        subcommands = {RollCommand.class},
        description = "Cold-tier utilities for the ascham arena: roll completed intervals into an "
                + "Iceberg warehouse.",
        footer = {
                "",
                "Exit codes:",
                "  0   success",
                "  1   the pass ran but at least one table failed, or setup failed",
                "  2   usage error (unknown or malformed arguments)",
        })
public final class AschamArchiveCli implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    /** Reached only when no subcommand was given — that is a usage error, not a run. */
    @Override
    public Integer call() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new AschamArchiveCli()).execute(args));
    }
}
