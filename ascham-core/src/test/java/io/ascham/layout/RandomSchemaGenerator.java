package io.ascham.layout;

import io.ascham.schema.ArenaSchema;
import io.ascham.schema.MetadataKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Generates random, always-valid {@link ArenaSchema}s from a seed, for the layout determinism
 * property test. Deterministic in the seed: same seed → same schema.
 */
final class RandomSchemaGenerator {

    private static final int[] BATCH_ROW_CHOICES = {16, 64, 1000, 65536};

    private RandomSchemaGenerator() {
    }

    static ArenaSchema generate(long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        List<Field> fields = new ArrayList<>();

        // Column 0 is always the timestamp time column.
        fields.add(field("ts", new ArrowType.Timestamp(
                rng.nextBoolean() ? TimeUnit.NANOSECOND : TimeUnit.MICROSECOND,
                rng.nextBoolean() ? "UTC" : null), Map.of()));

        int extra = 1 + rng.nextInt(7);
        String statsColumn = null;
        for (int i = 0; i < extra; i++) {
            String name = "c" + i;
            String family = rng.nextInt(4) == 0 ? "aux" : MetadataKeys.DEFAULT_FAMILY;
            ArrowType type = randomType(rng);
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(MetadataKeys.FAMILY, family);
            if (type.getTypeID() == ArrowType.ArrowTypeID.Utf8
                    || type.getTypeID() == ArrowType.ArrowTypeID.Binary) {
                meta.put(MetadataKeys.VARLEN_BYTES, Integer.toString(64 + rng.nextInt(8192)));
            } else if (statsColumn == null && isFixedWidthInteger(type)) {
                statsColumn = name;
            }
            fields.add(field(name, type, meta));
        }

        Map<String, String> schemaMeta = new LinkedHashMap<>();
        schemaMeta.put(MetadataKeys.TABLE, "t" + (seed & 0xFFFF));
        schemaMeta.put(MetadataKeys.SCHEMA_VERSION, Integer.toString(1 + rng.nextInt(9)));
        schemaMeta.put(MetadataKeys.TIME_COLUMN, "ts");
        schemaMeta.put(MetadataKeys.BATCH_ROWS,
                Integer.toString(BATCH_ROW_CHOICES[rng.nextInt(BATCH_ROW_CHOICES.length)]));
        if (statsColumn != null) {
            schemaMeta.put(MetadataKeys.STATS_COLUMN, statsColumn);
        }
        return ArenaSchema.load(new Schema(fields, schemaMeta));
    }

    private static ArrowType randomType(SplittableRandom rng) {
        return switch (rng.nextInt(11)) {
            case 0 -> new ArrowType.Bool();
            case 1 -> new ArrowType.Int(1 << (3 + rng.nextInt(4)), rng.nextBoolean()); // 8/16/32/64
            case 2 -> new ArrowType.FloatingPoint(
                    rng.nextBoolean() ? FloatingPointPrecision.SINGLE : FloatingPointPrecision.DOUBLE);
            case 3 -> new ArrowType.Decimal(38, 9, 128);
            case 4 -> new ArrowType.Date(DateUnit.DAY);
            case 5 -> new ArrowType.Time(TimeUnit.NANOSECOND, 64);
            case 6 -> new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC");
            case 7 -> new ArrowType.FixedSizeBinary(1 + rng.nextInt(48));
            case 8 -> new ArrowType.Utf8();
            case 9 -> new ArrowType.Binary();
            default -> new ArrowType.Int(32, true);
        };
    }

    private static boolean isFixedWidthInteger(ArrowType type) {
        return type.getTypeID() == ArrowType.ArrowTypeID.Int;
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }
}
