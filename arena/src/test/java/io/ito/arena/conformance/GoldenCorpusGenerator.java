package io.ito.arena.conformance;

import io.ito.arena.schema.CanonicalSchema;
import io.ito.arena.util.Sha256;
import io.ito.arena.write.SegmentWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Regenerates the golden corpus under {@code conformance/}. Run manually
 * ({@code ./gradlew regenerateGoldenCorpus}); any diff to the checked-in files is, by definition, a
 * format change and must be reviewed. {@link GoldenCorpusTest} regenerates into {@code build/} and
 * byte-compares against these files on every CI run.
 */
public final class GoldenCorpusGenerator {

    private GoldenCorpusGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: GoldenCorpusGenerator <conformanceDir>");
        }
        Path dir = Path.of(args[0]);
        List<GoldenCase> cases = GoldenCases.all();

        Files.createDirectories(dir.resolve("schemas"));
        Files.createDirectories(dir.resolve("golden"));
        StringBuilder manifest = new StringBuilder("{\n  \"cases\": [\n");

        for (int i = 0; i < cases.size(); i++) {
            GoldenCase c = cases.get(i);
            Path bin = dir.resolve("golden").resolve(c.name() + ".bin");
            Files.deleteIfExists(bin);
            buildSegment(c, bin);
            byte[] segment = Files.readAllBytes(bin);
            Files.write(dir.resolve("schemas").resolve(c.name() + ".arrows"),
                    CanonicalSchema.canonicalBytes(c.schema()));

            manifest.append("    {\"name\":\"").append(c.name())
                    .append("\",\"schema\":\"schemas/").append(c.name()).append(".arrows\"")
                    .append(",\"epoch\":").append(c.epoch())
                    .append(",\"sequence\":").append(c.sequence())
                    .append(",\"clockStart\":").append(c.clockStart())
                    .append(",\"clockStep\":").append(c.clockStep())
                    .append(",\"expectedTotalRows\":").append(c.expectedTotalRows())
                    .append(",\"segmentBytes\":").append(segment.length)
                    .append(",\"segmentSha256\":\"").append(Sha256.toHex(Sha256.hash(segment))).append("\"}")
                    .append(i < cases.size() - 1 ? ",\n" : "\n");
        }
        manifest.append("  ]\n}\n");
        Files.writeString(dir.resolve("manifest.json"), manifest.toString());
        System.out.println("Wrote " + cases.size() + " golden cases to " + dir);
    }

    /** Builds one case's segment at {@code binPath}. Shared by the generator and the test. */
    static void buildSegment(GoldenCase c, Path binPath) {
        try (SegmentWriter writer = SegmentWriter.createSegment(
                binPath, c.schema(), c.maxBatches(), c.epoch(), c.sequence(), c.clock())) {
            c.script().accept(writer);
        }
    }
}
