package io.ascham.conformance;

import io.ascham.schema.CanonicalSchema;
import io.ascham.util.Sha256;
import io.ascham.write.SegmentWriter;
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
        Files.createDirectories(dir.resolve("expected"));
        StringBuilder manifest = new StringBuilder("{\n  \"cases\": [\n");
        StringBuilder schemaHashes = new StringBuilder("{\n  \"cases\": {\n");

        for (int i = 0; i < cases.size(); i++) {
            GoldenCase c = cases.get(i);
            Path bin = dir.resolve("golden").resolve(c.name() + ".bin");
            Files.deleteIfExists(bin);
            buildSegment(c, bin);
            byte[] segment = Files.readAllBytes(bin);
            byte[] schemaBytes = CanonicalSchema.canonicalBytes(c.schema());
            Files.write(dir.resolve("schemas").resolve(c.name() + ".arrows"), schemaBytes);
            // The value side of the contract: what any reader must decode the golden bytes to.
            Files.writeString(dir.resolve("expected").resolve(c.name() + ".csv"), CsvExpected.render(bin));
            // Schema identity without an Arrow dependency (schema_sha256 as hex, per case).
            schemaHashes.append("    \"").append(c.name()).append("\": \"")
                    .append(Sha256.toHex(Sha256.hash(schemaBytes))).append('"')
                    .append(i < cases.size() - 1 ? ",\n" : "\n");

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
        schemaHashes.append("  }\n}\n");
        Files.writeString(dir.resolve("manifest.json"), manifest.toString());
        Files.writeString(dir.resolve("schema_hashes.json"), schemaHashes.toString());
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
