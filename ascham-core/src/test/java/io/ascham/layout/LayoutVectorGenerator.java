package io.ascham.layout;

import io.ascham.schema.ArenaSchema;
import io.ascham.schema.CanonicalSchema;
import io.ascham.schema.MetadataKeys;
import io.ascham.util.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Emits {@code conformance/layout_vectors.jsonl}: (canonical schema bytes → LayoutCodec descriptor
 * bytes) pairs that pin the layout-derivation algorithm across languages. {@code Layouts.compute}
 * and {@code LayoutCodec} are deliberately hand-written per language (never generated); a port
 * implements both and byte-compares against every vector here. The C++ reader consumes the vectors
 * as decoder-sanity input today; a future non-Java <em>writer</em> is the real audience.
 *
 * <p>One JSON object per line, no nesting: {@code id}, {@code schema_b64} (canonical Arrow IPC
 * schema message), {@code descriptor_b64} (LayoutCodec bytes), {@code descriptor_sha256}. Vectors
 * are the first {@link #RANDOM_SEEDS} seeds of the {@link RandomSchemaGenerator} used by the 10k
 * determinism property test, plus hand-picked edge cases. Run via
 * {@code ./gradlew regenerateLayoutVectors}; {@code LayoutVectorsTest} regenerates and
 * byte-compares on every CI run.
 */
public final class LayoutVectorGenerator {

    static final int RANDOM_SEEDS = 512;

    private LayoutVectorGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("usage: LayoutVectorGenerator <conformanceDir>");
        }
        Path out = Path.of(args[0]).resolve("layout_vectors.jsonl");
        Files.writeString(out, generate());
        System.out.println("Wrote " + (RANDOM_SEEDS + edgeCases().size()) + " layout vectors to " + out);
    }

    static String generate() {
        StringBuilder out = new StringBuilder();
        for (long seed = 0; seed < RANDOM_SEEDS; seed++) {
            append(out, "seed-" + seed, RandomSchemaGenerator.generate(seed));
        }
        edgeCases().forEach((id, schema) -> append(out, id, schema));
        return out.toString();
    }

    private static void append(StringBuilder out, String id, ArenaSchema schema) {
        Base64.Encoder b64 = Base64.getEncoder();
        byte[] schemaBytes = CanonicalSchema.canonicalBytes(schema);
        LayoutDescriptor descriptor = Layouts.compute(schema);
        byte[] encoded = new byte[LayoutCodec.encodedSize(descriptor)];
        LayoutCodec.encode(descriptor, new UnsafeBuffer(encoded), 0);
        out.append("{\"id\":\"").append(id)
                .append("\",\"schema_b64\":\"").append(b64.encodeToString(schemaBytes))
                .append("\",\"descriptor_b64\":\"").append(b64.encodeToString(encoded))
                .append("\",\"descriptor_sha256\":\"").append(Sha256.toHex(Sha256.hash(encoded)))
                .append("\"}\n");
    }

    /** Hand-picked boundary schemas the random generator is unlikely to hit exactly. */
    static Map<String, ArenaSchema> edgeCases() {
        Map<String, ArenaSchema> cases = new LinkedHashMap<>();
        cases.put("edge-ts-only", schema(1024, List.of()));
        cases.put("edge-batch-rows-1", schema(1, List.of(
                field("v", new ArrowType.Int(64, true), Map.of()))));
        // 65 rows: a 9-byte bitmap, so every subsequent buffer needs re-alignment.
        cases.put("edge-bitmap-boundary-65", schema(65, List.of(
                field("flag", new ArrowType.Bool(), Map.of()),
                field("v", new ArrowType.Int(32, true), Map.of()))));
        cases.put("edge-varlen-min-capacity", schema(16, List.of(
                field("s", new ArrowType.Utf8(), Map.of(MetadataKeys.VARLEN_BYTES, "1")))));
        cases.put("edge-bool-only", schema(65536, List.of(
                field("flag", new ArrowType.Bool(), Map.of()))));
        cases.put("edge-fsb-33", schema(64, List.of(
                field("b33", new ArrowType.FixedSizeBinary(33), Map.of()))));
        cases.put("edge-multi-family", schema(64, List.of(
                field("a", new ArrowType.Int(64, true), Map.of(MetadataKeys.FAMILY, "base")),
                field("b", new ArrowType.Int(64, true), Map.of(MetadataKeys.FAMILY, "aux")),
                field("c", new ArrowType.Utf8(),
                        Map.of(MetadataKeys.FAMILY, "aux", MetadataKeys.VARLEN_BYTES, "256")),
                field("d", new ArrowType.Int(64, true), Map.of(MetadataKeys.FAMILY, "ref")))));
        List<Field> wide = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            ArrowType type = switch (i % 4) {
                case 0 -> new ArrowType.Int(64, true);
                case 1 -> new ArrowType.Bool();
                case 2 -> new ArrowType.FixedSizeBinary(1 + i);
                default -> new ArrowType.Utf8();
            };
            Map<String, String> meta = type.getTypeID() == ArrowType.ArrowTypeID.Utf8
                    ? Map.of(MetadataKeys.VARLEN_BYTES, Integer.toString(64 + i))
                    : Map.of();
            wide.add(field("w" + i, type, meta));
        }
        cases.put("edge-wide-64-columns", schema(1000, wide));
        return cases;
    }

    private static ArenaSchema schema(int batchRows, List<Field> extra) {
        List<Field> fields = new ArrayList<>();
        fields.add(field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC"), Map.of()));
        fields.addAll(extra);
        Map<String, String> meta = new TreeMap<>();
        meta.put(MetadataKeys.TABLE, "layout_vectors");
        meta.put(MetadataKeys.SCHEMA_VERSION, "1");
        meta.put(MetadataKeys.TIME_COLUMN, "ts");
        meta.put(MetadataKeys.BATCH_ROWS, Integer.toString(batchRows));
        return ArenaSchema.load(new Schema(fields, meta));
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }
}
