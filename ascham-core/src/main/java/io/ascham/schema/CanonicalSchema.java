package io.ascham.schema;

import io.ascham.util.Sha256;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Canonical serialisation of a schema for hashing and for embedding in the segment. "Canonical"
 * means metadata-key order cannot affect the bytes: both schema-level and field-level metadata are
 * emitted in sorted key order, so two schemas that differ only in {@code custom_metadata} insertion
 * order produce identical bytes and therefore an identical SHA-256.
 *
 * <p>The hash is written into the segment header and re-verified at open — a mismatch is a hard
 * failure (spec invariant 7). The same canonical bytes are what the reader maps as the
 * self-describing embedded schema, so it needs no build-time coupling to the writer.
 */
public final class CanonicalSchema {

    private CanonicalSchema() {
    }

    /** Arrow IPC message bytes of the schema, with all metadata emitted in sorted key order. */
    public static byte[] canonicalBytes(ArenaSchema schema) {
        return canonicalize(schema.arrowSchema()).serializeAsMessage();
    }

    /** SHA-256 of {@link #canonicalBytes(ArenaSchema)}. */
    public static byte[] sha256(ArenaSchema schema) {
        return Sha256.hash(canonicalBytes(schema));
    }

    private static Schema canonicalize(Schema schema) {
        List<Field> fields = new ArrayList<>(schema.getFields().size());
        for (Field f : schema.getFields()) {
            fields.add(canonicalizeField(f));
        }
        return new Schema(fields, sorted(schema.getCustomMetadata()));
    }

    private static Field canonicalizeField(Field field) {
        FieldType original = field.getFieldType();
        FieldType canonical = new FieldType(
                original.isNullable(),
                original.getType(),
                original.getDictionary(),
                sorted(original.getMetadata()));
        List<Field> children = new ArrayList<>(field.getChildren().size());
        for (Field child : field.getChildren()) {
            children.add(canonicalizeField(child));
        }
        return new Field(field.getName(), canonical, children);
    }

    private static Map<String, String> sorted(Map<String, String> metadata) {
        return metadata == null || metadata.isEmpty() ? Map.of() : new TreeMap<>(metadata);
    }
}
