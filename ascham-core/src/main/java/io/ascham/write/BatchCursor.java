package io.ascham.write;

import io.ascham.layout.ColumnLayout;
import io.ascham.layout.LayoutDescriptor;
import io.ascham.schema.ArenaSchema;
import io.ascham.segment.CatalogCodec;
import io.ascham.segment.Regions;
import io.ascham.segment.SegmentFormat;
import io.ascham.segment.SegmentHeader;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.EpochNanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

/**
 * Sole owner of the publication protocol. Spec invariants 1–3 live here and nowhere else; see
 * {@code docs/segment-format.md} "Concurrency contract". The writer is single-threaded, so all guard
 * checks are plain booleans.
 *
 * <p>Steady-state {@code beginRow}/setters/{@code endRow} allocate nothing (spec: allocation-free hot
 * path). Batch open, seal, and the rare varlen-exhaustion migration may touch preallocated scratch
 * but never allocate per row.
 *
 * <p>Package-internal by design: the engine is reached only through {@link GenericAppender},
 * {@link RollingAppender}, and {@link SegmentWriter} (lifecycle). Producers never hold a
 * {@code BatchCursor}, so they cannot drive {@code seal}/{@code openBatch} or reorder the
 * publication protocol.
 */
final class BatchCursor {

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    /** Physical encoding of a column, finer-grained than {@code PhysicalKind}, for setter validation. */
    private enum Enc { BOOL, INT, FLOAT32, FLOAT64, DECIMAL128, FIXED_BINARY, VARLEN }

    private final LayoutDescriptor layout;
    private final UnsafeBuffer data;
    private final CatalogCodec catalog;
    private final SegmentHeader header;
    private final EpochNanoClock clock;

    private final long dataRegionOffset;
    private final int batchRows;
    private final long batchStride;
    private final int columnCount;
    private final ColumnLayout[] columns;
    private final Enc[] enc;
    private final int[] varlenOrdinals;
    private final int timeOrdinal;
    private final int statOrdinal;

    // Writer-local, heap-resident (never in the shared cache line).
    private final int[] varlenEnd;      // per column: byte end within its varlen data buffer
    private final int[] migrateSrc;     // scratch for migrateOpenRow (preallocated)
    private final int[] migrateLen;     // scratch for migrateOpenRow (preallocated)

    private int batchIndex = -1;
    private int batchBase;              // absolute (segment-relative) base of the current batch
    private int rowIndex;
    private boolean rowOpen;

    // Running per-batch stats (folded at endRow so a migrated open row is not counted twice).
    private long tsMin;
    private long tsMax;
    private boolean tsSeen;
    private long statMin;
    private long statMax;
    private boolean statSeen;

    // Per-row captured stat values (folded into running stats at endRow).
    private long rowTsValue;
    private boolean rowTsSet;
    private long rowStatValue;
    private boolean rowStatSet;

    BatchCursor(ArenaSchema schema, LayoutDescriptor layout, UnsafeBuffer data,
                CatalogCodec catalog, SegmentHeader header, Regions regions, EpochNanoClock clock) {
        this.layout = layout;
        this.data = data;
        this.catalog = catalog;
        this.header = header;
        this.clock = clock;
        this.dataRegionOffset = regions.dataOffset();
        this.batchRows = layout.batchRows();
        this.batchStride = layout.batchStrideBytes();
        this.columnCount = layout.columnCount();
        this.columns = layout.columns().toArray(new ColumnLayout[0]);
        this.enc = new Enc[columnCount];
        this.varlenEnd = new int[columnCount];
        this.migrateSrc = new int[columnCount];
        this.migrateLen = new int[columnCount];

        int varlenCount = 0;
        for (int i = 0; i < columnCount; i++) {
            enc[i] = classify(schema.field(i));
            if (enc[i] == Enc.VARLEN) {
                varlenCount++;
            }
        }
        this.varlenOrdinals = new int[varlenCount];
        for (int i = 0, v = 0; i < columnCount; i++) {
            if (enc[i] == Enc.VARLEN) {
                varlenOrdinals[v++] = i;
            }
        }
        this.timeOrdinal = ordinalOf(schema, schema.metadata().timeColumn());
        this.statOrdinal = schema.metadata().statsColumn().map(c -> ordinalOf(schema, c)).orElse(-1);
    }

    // --- Lifecycle ---

    void openBatch(int k) {
        if (k >= catalog.capacity()) {
            throw new io.ascham.segment.SegmentFullException(catalog.capacity());
        }
        batchIndex = k;
        batchBase = Math.toIntExact(dataRegionOffset + (long) k * batchStride);
        rowIndex = 0;
        rowOpen = false;
        tsMin = Long.MAX_VALUE;
        tsMax = Long.MIN_VALUE;
        tsSeen = false;
        statMin = Long.MAX_VALUE;
        statMax = Long.MIN_VALUE;
        statSeen = false;

        for (int ord : varlenOrdinals) {
            varlenEnd[ord] = 0;
            data.putInt(batchBase + (int) columns[ord].offsetsOffset(), 0, LE); // offsets[0] = 0
        }
        catalog.setBaseOffset(k, batchBase);
        catalog.setStats(k, 0, 0, 0, 0);
        catalog.setSealNanos(k, 0);
        catalog.publishLength(k, SegmentFormat.IN_PROGRESS_BIT);   // 0 rows, in progress
        header.publishActiveBatchCount(k + 1);
    }

    void beginRow() {
        if (rowOpen) {
            throw new IllegalStateException("row already open; call endRow() first");
        }
        if (rowIndex == batchRows) {
            seal(); // row-count seal (spec: seal on row count, not a timer)
        }
        rowOpen = true;
        rowTsSet = false;
        rowStatSet = false;
    }

    void endRow() {
        requireRowOpen();
        if (rowTsSet) {
            tsMin = Math.min(tsMin, rowTsValue);
            tsMax = Math.max(tsMax, rowTsValue);
            tsSeen = true;
        }
        if (rowStatSet) {
            statMin = Math.min(statMin, rowStatValue);
            statMax = Math.max(statMax, rowStatValue);
            statSeen = true;
        }
        // (2) publish offsets[n+1] for every varlen column before releasing length.
        for (int ord : varlenOrdinals) {
            data.putInt(batchBase + (int) columns[ord].offsetsOffset() + (rowIndex + 1) * Integer.BYTES,
                    varlenEnd[ord], LE);
        }
        rowIndex++;
        rowOpen = false;
        // (4) the one release-store — must be last.
        catalog.publishLength(batchIndex, (long) rowIndex | SegmentFormat.IN_PROGRESS_BIT);
    }

    /** Seals the in-progress batch (no copy) and opens the next. */
    void seal() {
        if (rowOpen) {
            throw new IllegalStateException("cannot seal mid-row");
        }
        sealAt(batchIndex, rowIndex);
        openBatch(batchIndex + 1);
    }

    /**
     * Seals the trailing in-progress batch without opening a successor — the close/rotate path.
     * Without this, the last batch of every rotated-away segment keeps {@code IN_PROGRESS_BIT} set
     * forever with unpublished (zeroed) stats: its rows stay readable, but it is invisible to
     * zone-map pruning and indistinguishable from a batch a live writer is still filling.
     *
     * <p>Deliberately does nothing for an <em>empty</em> trailing batch (which exists when the last
     * row exactly filled a batch, or after an explicit {@code seal()}): publishing a {@code [0, 0]}
     * time range would force every reader to special-case it, and zero-row batches are already
     * skipped by all consumers. So after this returns, the only batch that can still be in progress
     * is one holding no rows.
     *
     * <p>An open (begun but not ended) row is excluded: it was never published, so the sealed row
     * count is exactly the published prefix (invariant 1). Idempotent.
     */
    void sealFinal() {
        if (batchIndex < 0 || rowIndex == 0) {
            return;
        }
        sealAt(batchIndex, rowIndex);
        rowOpen = false;
        batchIndex = -1; // further appends are impossible; the segment is being closed
    }

    private void sealAt(int batch, int rowCount) {
        catalog.setStats(batch,
                tsSeen ? tsMin : 0, tsSeen ? tsMax : 0,
                statSeen ? statMin : 0, statSeen ? statMax : 0);
        catalog.setSealNanos(batch, clock.nanoTime());
        catalog.publishLength(batch, rowCount); // bit 63 cleared
    }

    // --- Setters (called by the appenders) ---

    void setBool(int col, boolean value) {
        requireRowOpen();
        requireEnc(col, Enc.BOOL);
        if (value) {
            setBit(batchBase + (int) columns[col].dataOffset(), rowIndex);
        }
        setValidity(col);
    }

    void setIntegral(int col, long value) {
        requireRowOpen();
        requireEnc(col, Enc.INT);
        writeIntegral(batchBase + cell(col), columns[col].elementWidth(), value);
        captureStat(col, value);
        setValidity(col);
    }

    void setFloat(int col, float value) {
        requireRowOpen();
        requireEnc(col, Enc.FLOAT32);
        data.putFloat(batchBase + cell(col), value, LE);
        setValidity(col);
    }

    void setDouble(int col, double value) {
        requireRowOpen();
        requireEnc(col, Enc.FLOAT64);
        data.putDouble(batchBase + cell(col), value, LE);
        setValidity(col);
    }

    void setDecimal128(int col, long low, long high) {
        requireRowOpen();
        requireEnc(col, Enc.DECIMAL128);
        int at = batchBase + cell(col);
        data.putLong(at, low, LE);
        data.putLong(at + Long.BYTES, high, LE);
        setValidity(col);
    }

    void setFixedBytes(int col, DirectBuffer src, int offset, int length) {
        requireRowOpen();
        requireEnc(col, Enc.FIXED_BINARY);
        int width = columns[col].elementWidth();
        if (length != width) {
            throw new IllegalArgumentException(
                    "FixedSizeBinary column " + col + " expects " + width + " bytes, got " + length);
        }
        data.putBytes(batchBase + cell(col), src, offset, length);
        setValidity(col);
    }

    void setBytes(int col, DirectBuffer src, int offset, int length) {
        requireRowOpen();
        requireEnc(col, Enc.VARLEN);
        ColumnLayout c = columns[col];
        if (length > c.varlenCapacityBytes()) {
            throw new IllegalArgumentException("varlen value (" + length + " bytes) exceeds column "
                    + col + " capacity " + c.varlenCapacityBytes());
        }
        if (varlenEnd[col] + (long) length > c.varlenCapacityBytes()) {
            // Doomed rows (own bytes alone overflow an empty batch) fail before any migration work.
            if (openRowVarlenBytes(col) + (long) length > c.varlenCapacityBytes()) {
                throw new IllegalStateException("row's varlen bytes for column " + col + " exceed capacity");
            }
            migrateOpenRow(); // varlen-capacity seal (whichever binds first), no rewind
        }
        data.putBytes(batchBase + (int) c.dataOffset() + varlenEnd[col], src, offset, length);
        varlenEnd[col] += length;
        setValidity(col);
    }

    /** Explicit null: leave validity unset (default). No-op, but part of the appender contract. */
    void setNull(int col) {
        requireRowOpen();
    }

    // --- Varlen-exhaustion migration (spec §4c): seal completed rows, move the open row, no rewind. ---

    private void migrateOpenRow() {
        int oldBase = batchBase;
        int oldRow = rowIndex;
        captureOpenRow();
        sealAt(batchIndex, oldRow);      // completed rows only
        openBatch(batchIndex + 1);       // resets rowIndex=0, varlenEnd=0, batchBase=new
        rowOpen = true;                  // the open row survives the migration
        replantOpenRow(this, oldBase, oldRow);
    }

    /** Records the open row's partial varlen extents into this cursor's scratch arrays. */
    private void captureOpenRow() {
        for (int ord : varlenOrdinals) {
            int start = openRowVarlenStart(ord);
            migrateSrc[ord] = batchBase + (int) columns[ord].dataOffset() + start;
            migrateLen[ord] = varlenEnd[ord] - start;
        }
    }

    /** Byte offset where the open row's data starts within {@code col}'s varlen buffer. */
    private int openRowVarlenStart(int col) {
        return data.getInt(batchBase + (int) columns[col].offsetsOffset() + rowIndex * Integer.BYTES, LE);
    }

    /** Varlen bytes the open row has already written to {@code col} — what a migration must carry. */
    private int openRowVarlenBytes(int col) {
        return varlenEnd[col] - openRowVarlenStart(col);
    }

    /**
     * Replants a captured open row (fixed cells, partial varlen bytes, validity bits) from
     * {@code src}'s batch at {@code (oldBase, oldRow)} into this cursor's current batch at row 0.
     * {@code src} may be this cursor (intra-segment migration) or the retiring segment's cursor
     * (cross-segment adoption); the layouts are identical by schema.
     */
    private void replantOpenRow(BatchCursor src, int oldBase, int oldRow) {
        for (int col = 0; col < columnCount; col++) {
            ColumnLayout c = columns[col];
            if (enc[col] == Enc.VARLEN) {
                int len = src.migrateLen[col];
                if (len > 0) {
                    data.putBytes(batchBase + (int) c.dataOffset(), src.data, src.migrateSrc[col], len);
                }
                varlenEnd[col] = len;
            } else {
                int width = c.elementWidth();
                if (enc[col] == Enc.BOOL) {
                    if (src.getBit(oldBase + (int) c.dataOffset(), oldRow)) {
                        setBit(batchBase + (int) c.dataOffset(), 0);
                    }
                } else {
                    data.putBytes(batchBase + (int) c.dataOffset(),
                            src.data, oldBase + (int) c.dataOffset() + oldRow * width, width);
                }
            }
            if (src.getBit(oldBase + (int) c.validityOffset(), oldRow)) {
                setBit(batchBase + (int) c.validityOffset(), 0);
            }
        }
    }

    // --- Rotation support (RollingAppender only): predict the SegmentFullException paths, adopt. ---

    /** True iff the next {@link #beginRow()} would seal and there is no next batch to open. */
    boolean rowCountRotationDue() {
        return batchIndex >= 0 && rowIndex == batchRows && batchIndex + 1 >= catalog.capacity();
    }

    /**
     * True iff {@code setBytes(col, …, length)} would migrate the open row with no next batch to
     * migrate into. False for rows no rotation can fix — a value larger than the column capacity,
     * or a row whose own accumulated bytes overflow an empty batch — those stay
     * {@link IllegalArgumentException}/{@link IllegalStateException} in {@link #setBytes}.
     */
    boolean varlenRotationDue(int col, int length) {
        if (enc[col] != Enc.VARLEN || !rowOpen) {
            return false;
        }
        long cap = columns[col].varlenCapacityBytes();
        if (varlenEnd[col] + (long) length <= cap) {
            return false; // fits in place
        }
        if (openRowVarlenBytes(col) + (long) length > cap) {
            return false; // doomed: setBytes rejects it without migrating
        }
        return batchIndex + 1 >= catalog.capacity();
    }

    boolean rowOpen() {
        return rowOpen;
    }

    /**
     * Adopts {@code source}'s open (begun, never ended) row into this cursor's batch 0, row 0 — the
     * cross-segment arm of the varlen-exhaustion migration, for when the source segment has no next
     * batch. The partial row was never published on the source (its length release-store only
     * happens at {@code endRow}), so nothing published is retracted (invariant 1); on this side the
     * data region is freshly zeroed, bits are only set (invariant 3), and the row becomes visible
     * through the normal {@code endRow} release-store (invariant 2). Pending row stats move with
     * the row so they fold into this segment's batch 0 at {@code endRow}.
     *
     * <p>Reads the source segment's mapping — must run before that segment is closed.
     */
    void adoptOpenRowFrom(BatchCursor source) {
        if (!source.rowOpen) {
            throw new IllegalStateException("source has no open row to adopt");
        }
        if (batchIndex != 0 || rowIndex != 0 || rowOpen) {
            throw new IllegalStateException("adopting cursor must be a fresh segment at batch 0, row 0");
        }
        if (columnCount != source.columnCount) {
            throw new IllegalStateException("adopting across different layouts");
        }
        source.captureOpenRow();
        rowOpen = true;
        replantOpenRow(source, source.batchBase, source.rowIndex);
        rowTsValue = source.rowTsValue;
        rowTsSet = source.rowTsSet;
        rowStatValue = source.rowStatValue;
        rowStatSet = source.rowStatSet;
        source.rowOpen = false; // the row lives here now; the source seals its completed prefix only
    }

    // --- Helpers ---

    private int cell(int col) {
        return (int) columns[col].dataOffset() + rowIndex * columns[col].elementWidth();
    }

    private void writeIntegral(int at, int width, long value) {
        switch (width) {
            case 1 -> data.putByte(at, (byte) value);
            case 2 -> data.putShort(at, (short) value, LE);
            case 4 -> data.putInt(at, (int) value, LE);
            case 8 -> data.putLong(at, value, LE);
            default -> throw new IllegalStateException("unexpected integral width " + width);
        }
    }

    private void captureStat(int col, long value) {
        if (col == timeOrdinal) {
            rowTsValue = value;
            rowTsSet = true;
        }
        if (col == statOrdinal) {
            rowStatValue = value;
            rowStatSet = true;
        }
    }

    private void setValidity(int col) {
        setBit(batchBase + (int) columns[col].validityOffset(), rowIndex);
    }

    /** Set-only byte RMW (spec invariant 3): only ever sets the new bit; no one else writes the byte. */
    private void setBit(int bufferOffset, int bitIndex) {
        int byteAt = bufferOffset + (bitIndex >>> 3);
        int mask = 1 << (bitIndex & 7);
        data.putByte(byteAt, (byte) (data.getByte(byteAt) | mask));
    }

    private boolean getBit(int bufferOffset, int bitIndex) {
        int byteAt = bufferOffset + (bitIndex >>> 3);
        return (data.getByte(byteAt) & (1 << (bitIndex & 7))) != 0;
    }

    private void requireRowOpen() {
        if (!rowOpen) {
            throw new IllegalStateException("no open row; call beginRow() first");
        }
    }

    private void requireEnc(int col, Enc expected) {
        if (enc[col] != expected) {
            throw new IllegalArgumentException(
                    "column " + col + " is " + enc[col] + ", not settable as " + expected);
        }
    }

    private static Enc classify(Field field) {
        ArrowType type = field.getType();
        return switch (type.getTypeID()) {
            case Bool -> Enc.BOOL;
            case Int, Date, Time, Timestamp -> Enc.INT;
            case FloatingPoint -> ((ArrowType.FloatingPoint) type).getPrecision() == FloatingPointPrecision.SINGLE
                    ? Enc.FLOAT32 : Enc.FLOAT64;
            case Decimal -> Enc.DECIMAL128;
            case FixedSizeBinary -> Enc.FIXED_BINARY;
            case Utf8, Binary -> Enc.VARLEN;
            default -> throw new IllegalStateException("unsupported type reached writer: " + type);
        };
    }

    private static int ordinalOf(ArenaSchema schema, String columnName) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.field(i).getName().equals(columnName)) {
                return i;
            }
        }
        throw new IllegalStateException("column not found (validator should have caught): " + columnName);
    }
}
