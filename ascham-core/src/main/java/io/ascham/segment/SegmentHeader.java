package io.ascham.segment;

import io.ascham.util.Sha256;
import java.util.Arrays;

/**
 * Typed access to the 4096-byte segment header over a {@link ControlRegion}. The static identity
 * fields are written once at create ({@link #writeInitial}) before the segment is renamed into
 * place, so they need no ordering; the heartbeat and {@code active_batch_count} are release/acquire.
 */
public final class SegmentHeader {

    private final ControlRegion control;

    public SegmentHeader(ControlRegion control) {
        this.control = control;
    }

    /** Writes the full static header (before the atomic rename). Heartbeat and count start at 0. */
    public static void writeInitial(
            ControlRegion control,
            byte[] schemaSha256,
            long segmentSequence,
            long arenaCapacity,
            long writerEpoch,
            long batchRows,
            long batchStride,
            Regions regions) {
        if (schemaSha256.length != Sha256.LENGTH) {
            throw new IllegalArgumentException("schema hash must be " + Sha256.LENGTH + " bytes");
        }
        control.putBytes(SegmentFormat.HDR_MAGIC, SegmentFormat.MAGIC);
        control.putInt(SegmentFormat.HDR_FORMAT_VERSION, SegmentFormat.FORMAT_VERSION);
        control.putInt(SegmentFormat.HDR_HEADER_LENGTH, SegmentFormat.HEADER_LENGTH);
        control.putBytes(SegmentFormat.HDR_SCHEMA_SHA256, schemaSha256);
        control.putLong(SegmentFormat.HDR_SEGMENT_SEQUENCE, segmentSequence);
        control.putLong(SegmentFormat.HDR_ARENA_CAPACITY, arenaCapacity);
        control.putLong(SegmentFormat.HDR_WRITER_EPOCH, writerEpoch);
        control.putLong(SegmentFormat.HDR_BATCH_ROWS, batchRows);
        control.putLong(SegmentFormat.HDR_BATCH_STRIDE, batchStride);
        control.putLong(SegmentFormat.HDR_HEARTBEAT, 0);
        control.putLong(SegmentFormat.HDR_ACTIVE_BATCH_COUNT, 0);

        writeRegion(control, SegmentFormat.REGION_SCHEMA, regions.schemaOffset(), regions.schemaLength());
        writeRegion(control, SegmentFormat.REGION_LAYOUT, regions.layoutOffset(), regions.layoutLength());
        writeRegion(control, SegmentFormat.REGION_CATALOG, regions.catalogOffset(), regions.catalogLength());
        writeRegion(control, SegmentFormat.REGION_DATA, regions.dataOffset(), regions.dataLength());
        writeRegion(control, SegmentFormat.REGION_FAMILY_WATERMARKS, 0, 0); // reserved in v1
    }

    /** Hard failure at open if magic or format version is wrong (invariant 7 precondition). */
    public void verifyMagicAndVersion() {
        byte[] magic = new byte[SegmentFormat.MAGIC_LENGTH];
        control.getBytes(SegmentFormat.HDR_MAGIC, magic);
        if (!Arrays.equals(magic, SegmentFormat.MAGIC)) {
            throw new SegmentFormatException("bad segment magic: " + new String(magic));
        }
        int version = control.getInt(SegmentFormat.HDR_FORMAT_VERSION);
        if (version != SegmentFormat.FORMAT_VERSION) {
            throw new SegmentFormatException("unsupported format version " + version);
        }
    }

    public byte[] schemaSha256() {
        byte[] hash = new byte[Sha256.LENGTH];
        control.getBytes(SegmentFormat.HDR_SCHEMA_SHA256, hash);
        return hash;
    }

    public long segmentSequence() {
        return control.getLong(SegmentFormat.HDR_SEGMENT_SEQUENCE);
    }

    public long arenaCapacity() {
        return control.getLong(SegmentFormat.HDR_ARENA_CAPACITY);
    }

    public long writerEpoch() {
        return control.getLong(SegmentFormat.HDR_WRITER_EPOCH);
    }

    public long batchRows() {
        return control.getLong(SegmentFormat.HDR_BATCH_ROWS);
    }

    public long batchStride() {
        return control.getLong(SegmentFormat.HDR_BATCH_STRIDE);
    }

    public Regions regions() {
        return new Regions(
                regionOffset(SegmentFormat.REGION_SCHEMA), regionLength(SegmentFormat.REGION_SCHEMA),
                regionOffset(SegmentFormat.REGION_LAYOUT), regionLength(SegmentFormat.REGION_LAYOUT),
                regionOffset(SegmentFormat.REGION_CATALOG), regionLength(SegmentFormat.REGION_CATALOG),
                regionOffset(SegmentFormat.REGION_DATA), regionLength(SegmentFormat.REGION_DATA));
    }

    // --- Liveness (ordered). ---

    public long heartbeatAcquire() {
        return control.getLongAcquire(SegmentFormat.HDR_HEARTBEAT);
    }

    public void bumpHeartbeat() {
        long next = control.getLong(SegmentFormat.HDR_HEARTBEAT) + 1;
        control.putLongRelease(SegmentFormat.HDR_HEARTBEAT, next);
    }

    public long activeBatchCountAcquire() {
        return control.getLongAcquire(SegmentFormat.HDR_ACTIVE_BATCH_COUNT);
    }

    public void publishActiveBatchCount(long count) {
        control.putLongRelease(SegmentFormat.HDR_ACTIVE_BATCH_COUNT, count);
    }

    private long regionOffset(int regionIndex) {
        return control.getLong(SegmentFormat.regionOffsetField(regionIndex));
    }

    private long regionLength(int regionIndex) {
        return control.getLong(SegmentFormat.regionLengthField(regionIndex));
    }

    private static void writeRegion(ControlRegion control, int regionIndex, long offset, long length) {
        control.putLong(SegmentFormat.regionOffsetField(regionIndex), offset);
        control.putLong(SegmentFormat.regionLengthField(regionIndex), length);
    }
}
