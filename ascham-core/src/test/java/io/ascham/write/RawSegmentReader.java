package io.ascham.write;

import io.ascham.layout.ColumnLayout;
import io.ascham.layout.LayoutDescriptor;
import io.ascham.segment.CatalogCodec;
import io.ascham.segment.Regions;
import io.ascham.segment.SegmentFile;
import io.ascham.segment.SegmentFormat;
import io.ascham.segment.SegmentHeader;
import java.nio.ByteOrder;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Minimal test-only reader that pulls raw cell values straight out of a segment's mapped buffers.
 * The real zero-copy {@code VectorSchemaRoot} reader arrives in M3; this exists so the M2 writer can
 * be verified byte-for-byte before then.
 */
final class RawSegmentReader {

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    private final LayoutDescriptor layout;
    private final Regions regions;
    private final UnsafeBuffer data;
    private final CatalogCodec catalog;
    private final SegmentHeader header;

    RawSegmentReader(SegmentFile file, LayoutDescriptor layout, Regions regions) {
        this.layout = layout;
        this.regions = regions;
        this.data = file.data();
        this.catalog = new CatalogCodec(file.control(), regions.catalogOffset(), regions.catalogCapacity());
        this.header = new SegmentHeader(file.control());
    }

    long activeBatchCount() {
        return header.activeBatchCountAcquire();
    }

    int rowCount(int batch) {
        return (int) SegmentFormat.rowCount(catalog.lengthAcquire(batch));
    }

    boolean inProgress(int batch) {
        return SegmentFormat.isInProgress(catalog.lengthAcquire(batch));
    }

    long tsMin(int batch) {
        return catalog.tsMin(batch);
    }

    long tsMax(int batch) {
        return catalog.tsMax(batch);
    }

    long statMin(int batch) {
        return catalog.statMin(batch);
    }

    long statMax(int batch) {
        return catalog.statMax(batch);
    }

    long sealNanos(int batch) {
        return catalog.sealNanos(batch);
    }

    boolean isValid(int batch, int row, int col) {
        return bit(batchBase(batch) + (int) column(col).validityOffset(), row);
    }

    boolean getBool(int batch, int row, int col) {
        return bit(batchBase(batch) + (int) column(col).dataOffset(), row);
    }

    long getIntegral(int batch, int row, int col) {
        ColumnLayout c = column(col);
        int at = batchBase(batch) + (int) c.dataOffset() + row * c.elementWidth();
        return switch (c.elementWidth()) {
            case 1 -> data.getByte(at);
            case 2 -> data.getShort(at, LE);
            case 4 -> data.getInt(at, LE);
            case 8 -> data.getLong(at, LE);
            default -> throw new IllegalStateException("width " + c.elementWidth());
        };
    }

    float getFloat(int batch, int row, int col) {
        ColumnLayout c = column(col);
        return data.getFloat(batchBase(batch) + (int) c.dataOffset() + row * c.elementWidth(), LE);
    }

    double getDouble(int batch, int row, int col) {
        ColumnLayout c = column(col);
        return data.getDouble(batchBase(batch) + (int) c.dataOffset() + row * c.elementWidth(), LE);
    }

    long[] getDecimal128(int batch, int row, int col) {
        ColumnLayout c = column(col);
        int at = batchBase(batch) + (int) c.dataOffset() + row * c.elementWidth();
        return new long[]{data.getLong(at, LE), data.getLong(at + Long.BYTES, LE)};
    }

    byte[] getFixedBytes(int batch, int row, int col) {
        ColumnLayout c = column(col);
        byte[] out = new byte[c.elementWidth()];
        data.getBytes(batchBase(batch) + (int) c.dataOffset() + row * c.elementWidth(), out);
        return out;
    }

    byte[] getVarlen(int batch, int row, int col) {
        ColumnLayout c = column(col);
        int base = batchBase(batch);
        int o0 = data.getInt(base + (int) c.offsetsOffset() + row * Integer.BYTES, LE);
        int o1 = data.getInt(base + (int) c.offsetsOffset() + (row + 1) * Integer.BYTES, LE);
        byte[] out = new byte[o1 - o0];
        data.getBytes(base + (int) c.dataOffset() + o0, out);
        return out;
    }

    private ColumnLayout column(int col) {
        return layout.column(col);
    }

    private int batchBase(int batch) {
        return Math.toIntExact(regions.dataOffset() + (long) batch * layout.batchStrideBytes());
    }

    private boolean bit(int bufferOffset, int bitIndex) {
        return (data.getByte(bufferOffset + (bitIndex >>> 3)) & (1 << (bitIndex & 7))) != 0;
    }
}
