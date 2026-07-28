package io.ito.arena.read;

import io.ito.arena.layout.ColumnLayout;
import io.ito.arena.layout.LayoutDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.ReferenceManager;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.message.ArrowFieldNode;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;

/**
 * One batch, exposed as a zero-copy Arrow {@link VectorSchemaRoot}. Each column's arena buffers are
 * wrapped as {@link ArrowBuf}s with {@link ReferenceManager#NO_OP} — pointing straight at the mapped
 * segment memory — and loaded via {@link VectorLoader}. No data is copied and no Arrow allocator ever
 * owns the data (the reader's allocator only holds empty vector metadata). Rows below this view's row
 * count are immutable for the life of the segment (invariant 1), so the underlying bytes never
 * change under the view.
 *
 * <p>Buffers are handed to Arrow in {@code TypeLayout} order: {validity, data} for fixed and bool
 * columns, {validity, offsets, data} for varlen columns.
 */
public final class BatchView {

    private final SnapshotReader reader;
    private final int batch;
    private final int rowCount;
    private final boolean sealed;
    private final long tsMin;
    private final long tsMax;
    private final long statMin;
    private final long statMax;
    private final long sealNanos;

    BatchView(SnapshotReader reader, int batch, int rowCount, boolean sealed,
              long tsMin, long tsMax, long statMin, long statMax, long sealNanos) {
        this.reader = reader;
        this.batch = batch;
        this.rowCount = rowCount;
        this.sealed = sealed;
        this.tsMin = tsMin;
        this.tsMax = tsMax;
        this.statMin = statMin;
        this.statMax = statMax;
        this.sealNanos = sealNanos;
    }

    public int batchIndex() {
        return batch;
    }

    public int rowCount() {
        return rowCount;
    }

    public boolean sealed() {
        return sealed;
    }

    public long tsMin() {
        return tsMin;
    }

    public long tsMax() {
        return tsMax;
    }

    public long statMin() {
        return statMin;
    }

    public long statMax() {
        return statMax;
    }

    public long sealNanos() {
        return sealNanos;
    }

    /**
     * Builds a zero-copy {@link VectorSchemaRoot} over this batch's rows. The caller owns the returned
     * root and should close it; closing releases only {@code NO_OP} buffers (a no-op) and the empty
     * vector metadata.
     */
    public VectorSchemaRoot root() {
        LayoutDescriptor layout = reader.layout();
        long dataBaseAddr = reader.file().data().addressOffset();
        long batchSegmentOffset = reader.regions().dataOffset() + (long) batch * layout.batchStrideBytes();
        long batchBaseAddr = dataBaseAddr + batchSegmentOffset;

        List<ArrowFieldNode> nodes = new ArrayList<>(layout.columnCount());
        List<ArrowBuf> buffers = new ArrayList<>();
        for (ColumnLayout col : layout.columns()) {
            nodes.add(new ArrowFieldNode(rowCount, nullCount(batchSegmentOffset, col)));
            buffers.add(wrap(batchBaseAddr + col.validityOffset(), validityCapacity(col)));
            if (col.isVarlen()) {
                buffers.add(wrap(batchBaseAddr + col.offsetsOffset(), col.dataOffset() - col.offsetsOffset()));
                buffers.add(wrap(batchBaseAddr + col.dataOffset(), col.dataCapacityBytes()));
            } else {
                buffers.add(wrap(batchBaseAddr + col.dataOffset(), col.dataCapacityBytes()));
            }
        }

        VectorSchemaRoot root = VectorSchemaRoot.create(reader.arenaSchema().arrowSchema(), reader.allocator());
        new VectorLoader(root).load(new ArrowRecordBatch(rowCount, nodes, buffers));
        // VectorLoader sets each vector's value count but not the root's row count in Arrow 19.
        root.setRowCount(rowCount);
        return root;
    }

    private static ArrowBuf wrap(long address, long capacity) {
        return new ArrowBuf(ReferenceManager.NO_OP, null, capacity, address);
    }

    /** Reserved validity-bitmap bytes: the gap from the validity buffer to the next buffer. */
    private static long validityCapacity(ColumnLayout col) {
        long next = col.isVarlen() ? col.offsetsOffset() : col.dataOffset();
        return next - col.validityOffset();
    }

    /** Counts nulls in {@code [0, rowCount)} from the validity bitmap (Arrow wants an exact count). */
    private int nullCount(long batchSegmentOffset, ColumnLayout col) {
        int validityBase = Math.toIntExact(batchSegmentOffset + col.validityOffset());
        int nulls = 0;
        for (int i = 0; i < rowCount; i++) {
            int b = reader.file().data().getByte(validityBase + (i >>> 3)) & 0xFF;
            if ((b & (1 << (i & 7))) == 0) {
                nulls++;
            }
        }
        return nulls;
    }
}
