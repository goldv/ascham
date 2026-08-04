package io.ascham.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.read.Snapshot;
import io.ascham.read.SnapshotReader;
import io.ascham.schema.CanonicalSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Validates the checked-in golden corpus: every case regenerates byte-for-byte, and each checked-in
 * segment opens through {@link SnapshotReader} with the expected structure. Any byte difference is a
 * format change — regenerate with {@code ./gradlew regenerateGoldenCorpus} and review the diff.
 */
class GoldenCorpusTest {

    private static final Path CONFORMANCE_DIR =
            Path.of(System.getProperty("io.ascham.conformance.dir", "conformance"));

    @TestFactory
    List<DynamicTest> goldenCorpus() throws Exception {
        Path work = Files.createTempDirectory("golden-regen");
        return GoldenCases.all().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> verify(c, work)))
                .toList();
    }

    private static void verify(GoldenCase c, Path work) throws Exception {
        Path golden = CONFORMANCE_DIR.resolve("golden").resolve(c.name() + ".bin");
        assertThat(golden)
                .as("checked-in golden for '%s' (run ./gradlew regenerateGoldenCorpus if missing)", c.name())
                .exists();

        // 1. Regenerate and byte-compare — the cross-language contract must be stable.
        Path regenerated = work.resolve(c.name() + ".bin");
        GoldenCorpusGenerator.buildSegment(c, regenerated);
        assertThat(Files.readAllBytes(regenerated))
                .as("segment bytes for '%s' match the checked-in golden", c.name())
                .isEqualTo(Files.readAllBytes(golden));

        // 2. Embedded schema matches the checked-in .arrows.
        assertThat(Files.readAllBytes(CONFORMANCE_DIR.resolve("schemas").resolve(c.name() + ".arrows")))
                .as("schema bytes for '%s'", c.name())
                .isEqualTo(CanonicalSchema.canonicalBytes(c.schema()));

        // 3. The checked-in segment opens and has the expected row structure.
        try (SnapshotReader reader = SnapshotReader.open(golden)) {
            Snapshot snapshot = reader.snapshot();
            int totalRows = snapshot.batches().stream().mapToInt(v -> v.rowCount()).sum();
            assertThat(totalRows).as("total rows in '%s'", c.name()).isEqualTo(c.expectedTotalRows());
        }
    }
}
