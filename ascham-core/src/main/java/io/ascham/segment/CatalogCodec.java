package io.ascham.segment;

/**
 * Typed access to the batch catalog over a {@link ControlRegion}. Entry {@code k} describes batch
 * {@code k} and occupies one 64-byte cache line at {@code catalogOffset + k * 64}.
 *
 * <p>{@link #publishLength} is the <em>only</em> release-store on the append path (spec invariant 2):
 * every data/offsets store and every plain field write below is made visible to a reader by the
 * subsequent release of {@code length}. All non-{@code length} fields are plain — safe because
 * {@code base_offset} is written before the first in-progress publish and is immutable thereafter,
 * and the stats/{@code seal_nanos} become meaningful only after the bit-63-clearing seal.
 */
public final class CatalogCodec {

    private final ControlRegion control;
    private final int catalogOffset;
    private final int capacity;

    public CatalogCodec(ControlRegion control, long catalogOffset, int capacity) {
        this.control = control;
        this.catalogOffset = Math.toIntExact(catalogOffset);
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    private int entry(int batch) {
        if (batch < 0 || batch >= capacity) {
            throw new IndexOutOfBoundsException("batch " + batch + " outside catalog capacity " + capacity);
        }
        return catalogOffset + batch * SegmentFormat.CATALOG_ENTRY_SIZE;
    }

    /** Release-store the catalog length (the single publication point). */
    public void publishLength(int batch, long lengthWithFlag) {
        control.putLongRelease(entry(batch) + SegmentFormat.ENT_LENGTH, lengthWithFlag);
    }

    /** Acquire-load the catalog length. */
    public long lengthAcquire(int batch) {
        return control.getLongAcquire(entry(batch) + SegmentFormat.ENT_LENGTH);
    }

    public void setBaseOffset(int batch, long baseOffset) {
        control.putLong(entry(batch) + SegmentFormat.ENT_BASE_OFFSET, baseOffset);
    }

    public long baseOffset(int batch) {
        return control.getLong(entry(batch) + SegmentFormat.ENT_BASE_OFFSET);
    }

    public void setStats(int batch, long tsMin, long tsMax, long statMin, long statMax) {
        int e = entry(batch);
        control.putLong(e + SegmentFormat.ENT_TS_MIN, tsMin);
        control.putLong(e + SegmentFormat.ENT_TS_MAX, tsMax);
        control.putLong(e + SegmentFormat.ENT_STAT_MIN, statMin);
        control.putLong(e + SegmentFormat.ENT_STAT_MAX, statMax);
    }

    public void setSealNanos(int batch, long sealNanos) {
        control.putLong(entry(batch) + SegmentFormat.ENT_SEAL_NANOS, sealNanos);
    }

    public long tsMin(int batch) {
        return control.getLong(entry(batch) + SegmentFormat.ENT_TS_MIN);
    }

    public long tsMax(int batch) {
        return control.getLong(entry(batch) + SegmentFormat.ENT_TS_MAX);
    }

    public long statMin(int batch) {
        return control.getLong(entry(batch) + SegmentFormat.ENT_STAT_MIN);
    }

    public long statMax(int batch) {
        return control.getLong(entry(batch) + SegmentFormat.ENT_STAT_MAX);
    }

    public long sealNanos(int batch) {
        return control.getLong(entry(batch) + SegmentFormat.ENT_SEAL_NANOS);
    }
}
