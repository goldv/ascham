package io.ito.arena.schema;

import static io.ito.arena.schema.SchemaFixtures.field;
import static io.ito.arena.schema.SchemaFixtures.int64;
import static io.ito.arena.schema.SchemaFixtures.meta;
import static io.ito.arena.schema.SchemaFixtures.tsNanos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    @Test
    void acceptsValidSchema() {
        SchemaValidator.Parsed parsed = SchemaValidator.validateAndParse(SchemaFixtures.validSchema());
        assertThat(parsed.metadata().table()).isEqualTo("trades");
        assertThat(parsed.metadata().schemaVersion()).isEqualTo(1);
        assertThat(parsed.metadata().batchRows()).isEqualTo(MetadataKeys.DEFAULT_BATCH_ROWS);
        assertThat(parsed.metadata().timeColumn()).isEqualTo("ts");
        assertThat(parsed.metadata().statsColumn()).isEqualTo(Optional.of("px"));
        assertThat(parsed.columns()).hasSize(3);
    }

    @Test
    void varlenColumnWithoutVarlenBytesIsError() {
        Schema schema = schemaWith(
                field("ts", tsNanos()),
                field("sym", new ArrowType.Utf8())); // no arena.varlen_bytes
        assertErrorContains(schema, MetadataKeys.VARLEN_BYTES, "required");
    }

    @Test
    void varlenBytesOnFixedColumnIsError() {
        Schema schema = schemaWith(
                field("ts", tsNanos()),
                field("px", int64(), meta(MetadataKeys.VARLEN_BYTES, "1024")));
        assertErrorContains(schema, MetadataKeys.VARLEN_BYTES, "only valid on Utf8/Binary");
    }

    @Test
    void timeColumnMustBeTimestamp() {
        Schema schema = new Schema(List.of(field("ts", int64())), meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts"));
        assertErrorContains(schema, "must be a Timestamp");
    }

    @Test
    void timeColumnIsRequired() {
        Schema schema = new Schema(List.of(field("ts", tsNanos())), meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1"));
        assertErrorContains(schema, MetadataKeys.TIME_COLUMN, "required");
    }

    @Test
    void statsColumnMustBeInteger() {
        Schema schema = new Schema(List.of(
                field("ts", tsNanos()),
                field("px", SchemaFixtures.float64())), meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                MetadataKeys.STATS_COLUMN, "px"));
        assertErrorContains(schema, "must be a fixed-width integer");
    }

    @Test
    void refOnlyValidOnInt32() {
        Schema schema = schemaWith(
                field("ts", tsNanos()),
                field("issuer", int64(), meta(MetadataKeys.REF, "issuers")));
        assertErrorContains(schema, MetadataKeys.REF, "signed Int32");
    }

    @Test
    void duplicateSortKeyIsError() {
        Schema schema = schemaWith(
                field("ts", tsNanos()),
                field("a", int64(), meta(MetadataKeys.SORT_KEY, "0")),
                field("b", int64(), meta(MetadataKeys.SORT_KEY, "0")));
        assertErrorContains(schema, "duplicate", MetadataKeys.SORT_KEY);
    }

    @Test
    void unknownSchemaKeyIsError() {
        Schema schema = new Schema(List.of(field("ts", tsNanos())), meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts",
                "arena.bogus", "x"));
        assertErrorContains(schema, "unknown metadata key", "arena.bogus");
    }

    @Test
    void unknownFieldKeyIsError() {
        Schema schema = schemaWith(
                field("ts", tsNanos()),
                field("px", int64(), meta("arena.nope", "1")));
        assertErrorContains(schema, "unknown metadata key", "arena.nope");
    }

    @Test
    void duplicateColumnNameIsError() {
        Schema schema = schemaWith(
                field("ts", tsNanos()),
                field("dup", int64()),
                field("dup", int64()));
        assertErrorContains(schema, "duplicate column name", "dup");
    }

    @Test
    void nonIntegerSchemaVersionIsError() {
        Schema schema = new Schema(List.of(field("ts", tsNanos())), meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "notanint",
                MetadataKeys.TIME_COLUMN, "ts"));
        assertErrorContains(schema, MetadataKeys.SCHEMA_VERSION, "not an integer");
    }

    @Test
    void unsupportedTypeIsError() {
        Schema schema = schemaWith(
                field("ts", tsNanos()),
                field("bad", new ArrowType.LargeUtf8()));
        assertErrorContains(schema, "bad");
    }

    @Test
    void reportsAllErrorsAtOnce() {
        Schema schema = new Schema(List.of(
                field("sym", new ArrowType.Utf8()),          // missing varlen_bytes
                field("bad", new ArrowType.LargeUtf8())),    // unsupported type
                meta(MetadataKeys.TABLE, "t"));               // missing schema_version + time_column
        SchemaValidationException ex = catchThrowableOfType(
                SchemaValidationException.class, () -> SchemaValidator.validateAndParse(schema));
        assertThat(ex.errors()).hasSizeGreaterThanOrEqualTo(4);
    }

    private static Schema schemaWith(Field... fields) {
        List<Field> list = new ArrayList<>(List.of(fields));
        return new Schema(list, meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts"));
    }

    private static void assertErrorContains(Schema schema, String... fragments) {
        SchemaValidationException ex = catchThrowableOfType(
                SchemaValidationException.class, () -> SchemaValidator.validateAndParse(schema));
        assertThat(ex).as("expected validation to fail").isNotNull();
        assertThat(ex.errors())
                .as("an error mentioning all of %s", (Object) fragments)
                .anySatisfy(err -> assertThat(err).contains(fragments));
    }

    @Test
    void loadThrowsOnInvalidSchema() {
        Schema schema = new Schema(List.of(field("ts", int64())), meta(
                MetadataKeys.TABLE, "t",
                MetadataKeys.SCHEMA_VERSION, "1",
                MetadataKeys.TIME_COLUMN, "ts"));
        assertThatThrownBy(() -> ArenaSchema.load(schema)).isInstanceOf(SchemaValidationException.class);
    }
}
