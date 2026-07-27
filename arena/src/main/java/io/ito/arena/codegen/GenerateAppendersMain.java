package io.ito.arena.codegen;

import io.ito.arena.schema.ArenaSchema;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * CLI entry point that generates typed appenders from serialised Arrow IPC schema files. This is the
 * recipe a consuming build wires as a {@code JavaExec} task; {@code :arena} itself uses it to
 * generate the appenders exercised by the equivalence test.
 *
 * <p>Usage: {@code GenerateAppendersMain <outputDir> <package> <schema.arrows>...}
 */
public final class GenerateAppendersMain {

    private GenerateAppendersMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: GenerateAppendersMain <outputDir> <package> <schema.arrows>...");
        }
        Path outputDir = Path.of(args[0]);
        String packageName = args[1];
        for (int i = 2; i < args.length; i++) {
            generateOne(Path.of(args[i]), outputDir, packageName);
        }
    }

    /** Generates one appender; returns the written source file. */
    public static Path generateOne(Path schemaFile, Path outputDir, String packageName) throws IOException {
        byte[] bytes = Files.readAllBytes(schemaFile);
        Schema arrow = Schema.deserializeMessage(ByteBuffer.wrap(bytes));
        ArenaSchema schema = ArenaSchema.load(arrow);
        String className = classNameFor(schema.metadata().table());
        String source = TypedAppenderGenerator.generate(schema, packageName, className);

        Path packageDir = outputDir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);
        Path target = packageDir.resolve(className + ".java");
        Files.writeString(target, source);
        return target;
    }

    static String classNameFor(String table) {
        StringBuilder b = new StringBuilder();
        boolean upper = true;
        for (int i = 0; i < table.length(); i++) {
            char c = table.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        return (b.isEmpty() ? "Table" : b.toString()) + "Appender";
    }
}
