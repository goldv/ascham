package io.ito.arena.layout;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.ColumnMetadata;
import io.ito.arena.schema.TypeProfile;
import io.ito.arena.util.Alignment;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * The pure, deterministic layout function: {@code (Arrow schema, arena metadata) → LayoutDescriptor}.
 * Same input always produces byte-identical output (a property test pins this). No I/O.
 *
 * <p>Within a batch, columns are laid out in schema ordinal order; each column emits its buffers in
 * the order validity → (offsets, for varlen) → data, and every buffer base is bumped to a 64-byte
 * boundary. The running cursor after the last column is page-padded to give the {@code batchStride}.
 */
public final class Layouts {

    private Layouts() {
    }

    public static LayoutDescriptor compute(ArenaSchema schema) {
        int batchRows = schema.metadata().batchRows();
        List<String> families = collectFamilies(schema);
        List<ColumnLayout> columns = new ArrayList<>(schema.columnCount());

        long cursor = 0;
        for (int ordinal = 0; ordinal < schema.columnCount(); ordinal++) {
            Field field = schema.field(ordinal);
            ColumnMetadata meta = schema.column(ordinal);
            PhysicalKind kind = TypeProfile.classify(field);
            int familyId = families.indexOf(meta.family());

            long validityOffset = Alignment.align64(cursor);
            cursor = validityOffset + Alignment.bitmapBytes(batchRows);

            ColumnLayout layout;
            switch (kind) {
                case FIXED -> {
                    int width = TypeProfile.fixedWidthBytes(field);
                    long dataOffset = Alignment.align64(cursor);
                    long capacity = (long) width * batchRows;
                    cursor = dataOffset + capacity;
                    layout = new ColumnLayout(field.getName(), ordinal, kind, familyId, width,
                            validityOffset, dataOffset, capacity, ColumnLayout.NO_OFFSETS, 0);
                }
                case BOOL_BITMAP -> {
                    long dataOffset = Alignment.align64(cursor);
                    long capacity = Alignment.bitmapBytes(batchRows);
                    cursor = dataOffset + capacity;
                    layout = new ColumnLayout(field.getName(), ordinal, kind, familyId, 0,
                            validityOffset, dataOffset, capacity, ColumnLayout.NO_OFFSETS, 0);
                }
                case VARLEN -> {
                    long offsetsOffset = Alignment.align64(cursor);
                    // n rows need n+1 int32 offsets (invariant 2: a reader at n finds offsets[n]).
                    cursor = offsetsOffset + (long) (batchRows + 1) * Integer.BYTES;
                    long dataOffset = Alignment.align64(cursor);
                    long capacity = meta.varlenBytes().orElseThrow(() -> new IllegalStateException(
                            "varlen column '" + field.getName() + "' reached layout without arena.varlen_bytes; "
                                    + "SchemaValidator should have rejected it"));
                    cursor = dataOffset + capacity;
                    layout = new ColumnLayout(field.getName(), ordinal, kind, familyId, 0,
                            validityOffset, dataOffset, capacity, offsetsOffset, capacity);
                }
                default -> throw new IllegalStateException("unreachable kind: " + kind);
            }
            columns.add(layout);
        }

        long batchStride = Alignment.alignPage(cursor);
        return new LayoutDescriptor(columns, batchRows, batchStride, families);
    }

    private static List<String> collectFamilies(ArenaSchema schema) {
        List<String> families = new ArrayList<>();
        for (ColumnMetadata column : schema.columns()) {
            if (!families.contains(column.family())) {
                families.add(column.family());
            }
        }
        return families;
    }
}
