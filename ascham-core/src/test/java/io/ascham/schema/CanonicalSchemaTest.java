package io.ascham.schema;

import static io.ascham.schema.SchemaFixtures.field;
import static io.ascham.schema.SchemaFixtures.int64;
import static io.ascham.schema.SchemaFixtures.meta;
import static io.ascham.schema.SchemaFixtures.tsNanos;
import static org.assertj.core.api.Assertions.assertThat;

import io.ascham.util.Sha256;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

class CanonicalSchemaTest {

    @Test
    void hashIsInsensitiveToSchemaMetadataOrder() {
        Schema a = new Schema(fields(), meta(
                MetadataKeys.TABLE, "trades",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "px"));
        Schema b = new Schema(fields(), meta(
                MetadataKeys.STATS_COLUMN, "px",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TABLE, "trades"));

        assertThat(Sha256.toHex(CanonicalSchema.sha256(ArenaSchema.load(a))))
                .isEqualTo(Sha256.toHex(CanonicalSchema.sha256(ArenaSchema.load(b))));
    }

    @Test
    void hashIsInsensitiveToFieldMetadataOrder() {
        Field a = field("sym", new ArrowType.Utf8(), meta(
                MetadataKeys.VARLEN_BYTES, "4096",
                MetadataKeys.FAMILY, "base"));
        Field b = field("sym", new ArrowType.Utf8(), meta(
                MetadataKeys.FAMILY, "base",
                MetadataKeys.VARLEN_BYTES, "4096"));

        assertThat(hashOf(a)).isEqualTo(hashOf(b));
    }

    @Test
    void differentContentProducesDifferentHash() {
        ArenaSchema one = ArenaSchema.load(SchemaFixtures.validSchema());
        Schema modified = new Schema(List.of(field("ts", tsNanos()), field("px", int64())), meta(
                MetadataKeys.TABLE, "trades",
                MetadataKeys.SCHEMA_VERSION, "2", // bumped
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "px"));

        assertThat(Sha256.toHex(CanonicalSchema.sha256(one)))
                .isNotEqualTo(Sha256.toHex(CanonicalSchema.sha256(ArenaSchema.load(modified))));
    }

    @Test
    void canonicalBytesAreStableAcrossCalls() {
        ArenaSchema schema = ArenaSchema.load(SchemaFixtures.validSchema());
        assertThat(CanonicalSchema.canonicalBytes(schema)).isEqualTo(CanonicalSchema.canonicalBytes(schema));
    }

    private static List<Field> fields() {
        return List.of(
                field("ts", tsNanos()),
                field("px", int64()),
                field("sym", new ArrowType.Utf8(), meta(MetadataKeys.VARLEN_BYTES, "4096")));
    }

    private static String hashOf(Field varlenField) {
        Map<String, String> schemaMeta = meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts");
        Schema schema = new Schema(List.of(field("ts", tsNanos()), varlenField), schemaMeta);
        return Sha256.toHex(CanonicalSchema.sha256(ArenaSchema.load(schema)));
    }
}
