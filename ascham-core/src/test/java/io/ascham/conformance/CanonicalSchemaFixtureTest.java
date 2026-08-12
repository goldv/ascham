package io.ascham.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.schema.CanonicalSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Pins the canonical schema bytes themselves. {@code GoldenCorpusTest} would also fail if they
 * moved, but through segment-byte and hash differences with no obvious cause; this test exists so
 * the failure is <em>named</em>: the canonical bytes are {@code Schema.serializeAsMessage()} output,
 * so an Arrow Java upgrade that perturbs flatbuffer emission shows up here first, as what it is —
 * a format break (every {@code schema_sha256} and golden hash moves with it). See
 * {@code format/segment-format.md} "Arrow schema region" and {@code arrow-java-version} in
 * {@code spec/format-manifest.toml}.
 */
class CanonicalSchemaFixtureTest {

    private static final Path CONFORMANCE_DIR =
            Path.of(System.getProperty("io.ascham.conformance.dir", "conformance"));

    @TestFactory
    List<DynamicTest> canonicalSchemaBytesArePinned() {
        return GoldenCases.all().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () ->
                        assertThat(CanonicalSchema.canonicalBytes(c.schema()))
                                .withFailMessage(
                                        "canonical schema bytes for '%s' changed — Schema.serializeAsMessage() "
                                                + "output is format contract, and a change (typically an Arrow Java "
                                                + "upgrade) is a FORMAT BREAK: every schema_sha256 and golden hash "
                                                + "moves with it. Review deliberately; see format/segment-format.md "
                                                + "\"Arrow schema region\".",
                                        c.name())
                                .isEqualTo(Files.readAllBytes(
                                        CONFORMANCE_DIR.resolve("schemas").resolve(c.name() + ".arrows")))))
                .toList();
    }
}
