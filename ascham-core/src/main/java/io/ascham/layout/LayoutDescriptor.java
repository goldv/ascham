package io.ascham.layout;

import java.util.List;

/**
 * The complete byte layout of a batch: per-column layouts plus the fixed per-batch stride. Batches
 * are laid out in the data region at {@code batchStrideBytes} intervals, accumulated in place and
 * never rewound (spec invariant 1). The stride is page-padded so a batch never ends flush against
 * an unmapped page (invariant 6).
 *
 * @param columns          per-column layouts, in schema ordinal order
 * @param batchRows        capacity in rows of each batch ({@code ascham.batch_rows})
 * @param batchStrideBytes fixed byte reservation per batch (page-aligned)
 * @param families         column-family names, indexed by {@code familyId} (invariant 8)
 */
public record LayoutDescriptor(
        List<ColumnLayout> columns,
        int batchRows,
        long batchStrideBytes,
        List<String> families) {

    public LayoutDescriptor {
        columns = List.copyOf(columns);
        families = List.copyOf(families);
    }

    public int columnCount() {
        return columns.size();
    }

    public ColumnLayout column(int ordinal) {
        return columns.get(ordinal);
    }
}
