package io.ito.arena.schema;

import java.util.List;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * A validated Arrow schema paired with its parsed {@code arena.*} metadata. This is the source of
 * truth from which the byte layout, appender signatures, and reader types are all derived: the
 * arena is an interpreter of schemas, nothing domain-specific.
 *
 * <p>Construct only via {@link #load(Schema)}, which runs strict total validation
 * ({@link SchemaValidator}); an {@code ArenaSchema} instance therefore always satisfies the v1
 * profile and metadata rules.
 */
public final class ArenaSchema {

    private final Schema schema;
    private final ArenaMetadata metadata;
    private final List<ColumnMetadata> columns;

    private ArenaSchema(Schema schema, ArenaMetadata metadata, List<ColumnMetadata> columns) {
        this.schema = schema;
        this.metadata = metadata;
        this.columns = columns;
    }

    /**
     * Validates and loads a schema.
     *
     * @throws SchemaValidationException if any rule is violated (carrying <em>all</em> errors)
     */
    public static ArenaSchema load(Schema schema) {
        SchemaValidator.Parsed parsed = SchemaValidator.validateAndParse(schema);
        return new ArenaSchema(schema, parsed.metadata(), List.copyOf(parsed.columns()));
    }

    public Schema arrowSchema() {
        return schema;
    }

    public ArenaMetadata metadata() {
        return metadata;
    }

    public List<Field> fields() {
        return schema.getFields();
    }

    public int columnCount() {
        return columns.size();
    }

    public Field field(int ordinal) {
        return schema.getFields().get(ordinal);
    }

    public ColumnMetadata column(int ordinal) {
        return columns.get(ordinal);
    }

    public List<ColumnMetadata> columns() {
        return columns;
    }
}
