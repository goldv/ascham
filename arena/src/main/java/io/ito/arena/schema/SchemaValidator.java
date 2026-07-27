package io.ito.arena.schema;

import io.ito.arena.layout.PhysicalKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Strict, total validation of an Arrow schema plus its {@code arena.*} metadata. Collects
 * <em>every</em> error and throws once via {@link SchemaValidationException}. Fail at load, never
 * at append.
 *
 * <p>Rules enforced (spec "Validation must be strict and total"):
 * <ul>
 *   <li>every field type is inside the v1 profile ({@link TypeProfile}); dictionary encoding rejected</li>
 *   <li>field names are unique</li>
 *   <li>{@code arena.table} present and non-empty; {@code arena.schema_version} a valid integer</li>
 *   <li>{@code arena.batch_rows}, if present, a positive integer</li>
 *   <li>{@code arena.time_column} present and naming a {@code Timestamp} column</li>
 *   <li>{@code arena.stats_column}, if present, naming a fixed-width integer column</li>
 *   <li>{@code arena.varlen_bytes} present and positive for every {@code Utf8}/{@code Binary}
 *       column, and absent on all others</li>
 *   <li>{@code arena.sort_key}, if present, a non-negative integer, unique across columns</li>
 *   <li>{@code arena.ref} only on signed {@code Int32} columns</li>
 *   <li>no unknown {@code arena.*} keys at either level</li>
 * </ul>
 */
public final class SchemaValidator {

    /** Parsed, validated metadata returned on success. */
    public record Parsed(ArenaMetadata metadata, List<ColumnMetadata> columns) {
    }

    private SchemaValidator() {
    }

    public static Parsed validateAndParse(Schema schema) {
        List<String> errors = new ArrayList<>();
        List<Field> fields = schema.getFields();

        Map<String, Field> byName = indexByName(fields, errors);
        List<ColumnMetadata> columns = parseColumns(fields, errors);

        Map<String, String> schemaMeta = schema.getCustomMetadata();
        rejectUnknownKeys(schemaMeta.keySet(), MetadataKeys.SCHEMA_LEVEL, "schema-level", errors);

        String table = requireNonBlank(schemaMeta, MetadataKeys.TABLE, errors);
        int schemaVersion = requireInt(schemaMeta, MetadataKeys.SCHEMA_VERSION, errors);
        int batchRows = parseBatchRows(schemaMeta, errors);
        String timeColumn = validateTimeColumn(schemaMeta, byName, errors);
        Optional<String> statsColumn = validateStatsColumn(schemaMeta, byName, errors);

        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }
        ArenaMetadata meta = new ArenaMetadata(table, schemaVersion, batchRows, timeColumn, statsColumn);
        return new Parsed(meta, columns);
    }

    private static Map<String, Field> indexByName(List<Field> fields, List<String> errors) {
        Map<String, Field> byName = new HashMap<>();
        for (Field f : fields) {
            if (byName.putIfAbsent(f.getName(), f) != null) {
                errors.add("duplicate column name '" + f.getName() + "'");
            }
        }
        return byName;
    }

    private static List<ColumnMetadata> parseColumns(List<Field> fields, List<String> errors) {
        List<ColumnMetadata> columns = new ArrayList<>(fields.size());
        Set<Integer> seenSortKeys = new HashSet<>();
        for (Field f : fields) {
            PhysicalKind kind = classify(f, errors);
            Map<String, String> meta = f.getMetadata();
            String where = "column '" + f.getName() + "'";
            rejectUnknownKeys(meta.keySet(), MetadataKeys.FIELD_LEVEL, where, errors);

            OptionalLong varlenBytes = parseVarlenBytes(f, kind, meta, errors);
            OptionalInt sortKey = parseSortKey(f, meta, seenSortKeys, errors);
            String family = meta.getOrDefault(MetadataKeys.FAMILY, MetadataKeys.DEFAULT_FAMILY);
            Optional<String> ref = parseRef(f, meta, errors);

            columns.add(new ColumnMetadata(f.getName(), varlenBytes, sortKey, family, ref));
        }
        return columns;
    }

    /** Classifies a field, recording any type error and returning {@code null} on failure. */
    private static PhysicalKind classify(Field f, List<String> errors) {
        try {
            return TypeProfile.classify(f);
        } catch (UnsupportedTypeException e) {
            errors.add(e.getMessage());
            return null;
        }
    }

    private static OptionalLong parseVarlenBytes(
            Field f, PhysicalKind kind, Map<String, String> meta, List<String> errors) {
        String raw = meta.get(MetadataKeys.VARLEN_BYTES);
        boolean isVarlen = kind == PhysicalKind.VARLEN;
        if (raw == null) {
            if (isVarlen) {
                errors.add("column '" + f.getName() + "': " + MetadataKeys.VARLEN_BYTES
                        + " is required for Utf8/Binary columns");
            }
            return OptionalLong.empty();
        }
        if (!isVarlen && kind != null) {
            errors.add("column '" + f.getName() + "': " + MetadataKeys.VARLEN_BYTES
                    + " is only valid on Utf8/Binary columns");
        }
        try {
            long v = Long.parseLong(raw);
            if (v <= 0) {
                errors.add("column '" + f.getName() + "': " + MetadataKeys.VARLEN_BYTES
                        + " must be positive, got " + v);
                return OptionalLong.empty();
            }
            return OptionalLong.of(v);
        } catch (NumberFormatException e) {
            errors.add("column '" + f.getName() + "': " + MetadataKeys.VARLEN_BYTES
                    + " is not an integer: '" + raw + "'");
            return OptionalLong.empty();
        }
    }

    private static OptionalInt parseSortKey(
            Field f, Map<String, String> meta, Set<Integer> seen, List<String> errors) {
        String raw = meta.get(MetadataKeys.SORT_KEY);
        if (raw == null) {
            return OptionalInt.empty();
        }
        try {
            int v = Integer.parseInt(raw);
            if (v < 0) {
                errors.add("column '" + f.getName() + "': " + MetadataKeys.SORT_KEY
                        + " must be non-negative, got " + v);
                return OptionalInt.empty();
            }
            if (!seen.add(v)) {
                errors.add("column '" + f.getName() + "': duplicate " + MetadataKeys.SORT_KEY
                        + " ordinal " + v);
            }
            return OptionalInt.of(v);
        } catch (NumberFormatException e) {
            errors.add("column '" + f.getName() + "': " + MetadataKeys.SORT_KEY
                    + " is not an integer: '" + raw + "'");
            return OptionalInt.empty();
        }
    }

    private static Optional<String> parseRef(Field f, Map<String, String> meta, List<String> errors) {
        String ref = meta.get(MetadataKeys.REF);
        if (ref == null) {
            return Optional.empty();
        }
        if (!isSignedInt32(f)) {
            errors.add("column '" + f.getName() + "': " + MetadataKeys.REF
                    + " is only valid on signed Int32 columns");
        }
        return Optional.of(ref);
    }

    private static int parseBatchRows(Map<String, String> schemaMeta, List<String> errors) {
        String raw = schemaMeta.get(MetadataKeys.BATCH_ROWS);
        if (raw == null) {
            return MetadataKeys.DEFAULT_BATCH_ROWS;
        }
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) {
                errors.add(MetadataKeys.BATCH_ROWS + " must be positive, got " + v);
                return MetadataKeys.DEFAULT_BATCH_ROWS;
            }
            return v;
        } catch (NumberFormatException e) {
            errors.add(MetadataKeys.BATCH_ROWS + " is not an integer: '" + raw + "'");
            return MetadataKeys.DEFAULT_BATCH_ROWS;
        }
    }

    private static String validateTimeColumn(
            Map<String, String> schemaMeta, Map<String, Field> byName, List<String> errors) {
        String name = schemaMeta.get(MetadataKeys.TIME_COLUMN);
        if (name == null || name.isBlank()) {
            errors.add(MetadataKeys.TIME_COLUMN + " is required and must name a Timestamp column");
            return "";
        }
        Field f = byName.get(name);
        if (f == null) {
            errors.add(MetadataKeys.TIME_COLUMN + " names unknown column '" + name + "'");
        } else if (f.getType().getTypeID() != ArrowType.ArrowTypeID.Timestamp) {
            errors.add(MetadataKeys.TIME_COLUMN + " '" + name + "' must be a Timestamp column, got "
                    + f.getType().getTypeID());
        }
        return name;
    }

    private static Optional<String> validateStatsColumn(
            Map<String, String> schemaMeta, Map<String, Field> byName, List<String> errors) {
        String name = schemaMeta.get(MetadataKeys.STATS_COLUMN);
        if (name == null) {
            return Optional.empty();
        }
        Field f = byName.get(name);
        if (f == null) {
            errors.add(MetadataKeys.STATS_COLUMN + " names unknown column '" + name + "'");
        } else if (!isFixedWidthInteger(f)) {
            errors.add(MetadataKeys.STATS_COLUMN + " '" + name
                    + "' must be a fixed-width integer column, got " + f.getType().getTypeID());
        }
        return Optional.of(name);
    }

    private static void rejectUnknownKeys(
            Set<String> present, Set<String> allowed, String where, List<String> errors) {
        for (String key : present) {
            if (key.startsWith(MetadataKeys.PREFIX) && !allowed.contains(key)) {
                errors.add(where + " has unknown metadata key '" + key + "'");
            }
        }
    }

    private static String requireNonBlank(Map<String, String> meta, String key, List<String> errors) {
        String v = meta.get(key);
        if (v == null || v.isBlank()) {
            errors.add(key + " is required and must be non-empty");
            return "";
        }
        return v;
    }

    private static int requireInt(Map<String, String> meta, String key, List<String> errors) {
        String raw = meta.get(key);
        if (raw == null) {
            errors.add(key + " is required");
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            errors.add(key + " is not an integer: '" + raw + "'");
            return 0;
        }
    }

    private static boolean isFixedWidthInteger(Field f) {
        if (f.getType().getTypeID() != ArrowType.ArrowTypeID.Int) {
            return false;
        }
        int bits = ((ArrowType.Int) f.getType()).getBitWidth();
        return bits == 8 || bits == 16 || bits == 32 || bits == 64;
    }

    private static boolean isSignedInt32(Field f) {
        if (f.getType().getTypeID() != ArrowType.ArrowTypeID.Int) {
            return false;
        }
        ArrowType.Int t = (ArrowType.Int) f.getType();
        return t.getBitWidth() == 32 && t.getIsSigned();
    }
}
