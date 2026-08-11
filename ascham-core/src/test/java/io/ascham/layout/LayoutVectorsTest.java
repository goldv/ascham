package io.ascham.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the checked-in {@code conformance/layout_vectors.jsonl}: regeneration must be byte-identical.
 * A difference means {@code Layouts.compute} or {@code LayoutCodec} changed behaviour — by
 * definition a format change (bump {@code LAYOUT_CODEC_VERSION}, regenerate with
 * {@code ./gradlew regenerateLayoutVectors}, and review the diff).
 */
class LayoutVectorsTest {

    private static final Path CONFORMANCE_DIR =
            Path.of(System.getProperty("io.ascham.conformance.dir", "conformance"));

    @Test
    void layoutVectorsRegenerateByteIdentically() throws Exception {
        Path vectors = CONFORMANCE_DIR.resolve("layout_vectors.jsonl");
        assertThat(vectors)
                .as("checked-in layout vectors (run ./gradlew regenerateLayoutVectors if missing)")
                .exists();
        assertThat(LayoutVectorGenerator.generate())
                .as("layout vectors regenerate byte-identically")
                .isEqualTo(Files.readString(vectors));
    }
}
