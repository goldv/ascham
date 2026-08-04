package io.ascham.samples;

import io.ascham.schema.ArenaSchema;
import io.ascham.schema.MetadataKeys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * The demo's market-data schemas: {@code quotes} (top of book) and {@code trades} (executions),
 * per docs/flight-sql-design-plan.md §7.
 *
 * <p>Three conventions worth knowing before reading them:
 *
 * <ul>
 *   <li><b>Prices are scaled integers, not floats.</b> {@code Int64} with an implied 1e-4 scale, so
 *       {@code 1234567} is 123.4567. Money in binary floating point accumulates error and compares
 *       badly; a scaled integer is exact and sorts correctly. Queries divide by 10000.0 to display.</li>
 *   <li><b>{@code ascham.varlen_bytes} is a per-batch budget for the whole column</b>, not per row —
 *       size it as {@code batch_rows × max value size}. Undersize it and the writer migrates rows to
 *       a new batch more often than it should; oversize it and every batch reserves memory it never
 *       uses.</li>
 *   <li><b>{@code ascham.sort_key} declares the natural sort order</b> ({@code sym} then {@code ts}),
 *       which is what the cold tier sorts rolled files by. Putting it in the schema means the
 *       ordering travels with the data instead of living in a separate config that can drift.</li>
 * </ul>
 */
public final class DemoSchemas {

    /** Implied decimal scale of every price column: value 1234567 means 123.4567. */
    public static final int PRICE_SCALE = 4;
    public static final long PRICE_MULTIPLIER = 10_000L;

    private static final String SCALE_KEY = "demo.price_scale";

    private DemoSchemas() {
    }

    /** Top-of-book quotes. Zone maps track {@code ts}; {@code bid_px} is the stats column. */
    public static ArenaSchema quotes(int batchRows) {
        int symBytes = batchRows * 8;    // ticker symbols are short
        int venueBytes = batchRows * 8;  // "XNAS", "ARCA", "BATS"
        List<Field> fields = List.of(
                timestamp("ts", sortKey(1)),
                utf8("sym", symBytes, sortKey(0)),
                price("bid_px"),
                price("ask_px"),
                int32("bid_sz"),
                int32("ask_sz"),
                utf8("venue", venueBytes, Map.of()));
        return ArenaSchema.load(new Schema(fields, tableMetadata("quotes", batchRows, "bid_px")));
    }

    /** Executions. Zone maps track {@code ts}; {@code px} is the stats column. */
    public static ArenaSchema trades(int batchRows) {
        int symBytes = batchRows * 8;
        int venueBytes = batchRows * 8;
        int sideBytes = batchRows * 2;   // "B" / "S"
        List<Field> fields = List.of(
                timestamp("ts", sortKey(1)),
                utf8("sym", symBytes, sortKey(0)),
                price("px"),
                int32("sz"),
                utf8("side", sideBytes, Map.of()),
                int64("trade_id"),
                utf8("venue", venueBytes, Map.of()));
        return ArenaSchema.load(new Schema(fields, tableMetadata("trades", batchRows, "px")));
    }

    /**
     * Columns in {@code ascham.sort_key} order — the sort the cold tier should write files in.
     * Falls back to the time column when a schema declares no sort keys.
     */
    public static List<String> sortColumns(ArenaSchema schema) {
        Map<Integer, String> byKey = new java.util.TreeMap<>(); // keyed by ordinal, so iteration is ordered
        for (var column : schema.columns()) {
            column.sortKey().ifPresent(k -> byKey.put(k, column.name()));
        }
        return byKey.isEmpty() ? List.of(schema.metadata().timeColumn()) : List.copyOf(byKey.values());
    }

    private static Map<String, String> tableMetadata(String table, int batchRows, String statsColumn) {
        Map<String, String> md = new LinkedHashMap<>();
        md.put(MetadataKeys.TABLE, table);
        md.put(MetadataKeys.SCHEMA_VERSION, "1");
        md.put(MetadataKeys.BATCH_ROWS, Integer.toString(batchRows));
        md.put(MetadataKeys.TIME_COLUMN, "ts");
        md.put(MetadataKeys.STATS_COLUMN, statsColumn);
        return md;
    }

    private static Map<String, String> sortKey(int ordinal) {
        return Map.of(MetadataKeys.SORT_KEY, Integer.toString(ordinal));
    }

    private static Field timestamp(String name, Map<String, String> extra) {
        return field(name, new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC"), extra);
    }

    private static Field price(String name) {
        return field(name, new ArrowType.Int(64, true), Map.of(SCALE_KEY, Integer.toString(PRICE_SCALE)));
    }

    private static Field int32(String name) {
        return field(name, new ArrowType.Int(32, true), Map.of());
    }

    private static Field int64(String name) {
        return field(name, new ArrowType.Int(64, true), Map.of());
    }

    private static Field utf8(String name, int varlenBytes, Map<String, String> extra) {
        Map<String, String> md = new LinkedHashMap<>(extra);
        md.put(MetadataKeys.VARLEN_BYTES, Integer.toString(varlenBytes));
        return field(name, new ArrowType.Utf8(), md);
    }

    private static Field field(String name, ArrowType type, Map<String, String> metadata) {
        return new Field(name, new FieldType(true, type, null, metadata), List.of());
    }
}
