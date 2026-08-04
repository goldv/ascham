package io.ascham.read;

import io.ascham.segment.CatalogCodec;
import io.ascham.segment.SegmentFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * A frozen view of the catalog, taken once and never re-read (spec: "a snapshot must never re-read a
 * catalog entry; a stale snapshot is always safe, an inconsistent one is not"). Construction
 * acquire-loads {@code active_batch_count} and every entry's {@code length} exactly once, resolves
 * row counts, and captures the (immutable, for sealed batches) catalog stats.
 */
public final class Snapshot {

    private final SnapshotReader reader;
    private final List<BatchView> batches;

    Snapshot(SnapshotReader reader) {
        this.reader = reader;
        this.batches = freeze(reader);
    }

    private static List<BatchView> freeze(SnapshotReader reader) {
        CatalogCodec catalog = new CatalogCodec(
                reader.file().control(), reader.regions().catalogOffset(), reader.regions().catalogCapacity());
        int count = (int) reader.header().activeBatchCountAcquire();
        List<BatchView> views = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            // Acquire the length first; it happens-after every plain write to this entry (invariant 2),
            // so the stats read below are visible and consistent for a sealed batch.
            long length = catalog.lengthAcquire(k);
            int rowCount = resolveRowCount(length);
            boolean sealed = !SegmentFormat.isInProgress(length);
            views.add(new BatchView(reader, k, rowCount, sealed,
                    catalog.tsMin(k), catalog.tsMax(k),
                    catalog.statMin(k), catalog.statMax(k), catalog.sealNanos(k)));
        }
        return views;
    }

    /**
     * Row count exposed to the reader. v1 is a single-family identity; multi-family (invariant 8)
     * inserts a {@code min} across the per-family watermark table here.
     */
    private static int resolveRowCount(long length) {
        return (int) SegmentFormat.rowCount(length);
    }

    public int batchCount() {
        return batches.size();
    }

    public List<BatchView> batches() {
        return batches;
    }

    /**
     * Filters batches on catalog min/max. Sealed batches survive only if they overlap both ranges;
     * a {@code null} range is a wildcard. In-progress batches are never pruned — their stats are
     * unpublished until seal, so they are always included and read live.
     */
    public List<BatchView> prune(TimeRange time, StatRange stat) {
        List<BatchView> result = new ArrayList<>();
        for (BatchView v : batches) {
            if (!v.sealed()) {
                result.add(v);
                continue;
            }
            if (time != null && !overlaps(v.tsMin(), v.tsMax(), time.lo(), time.hi())) {
                continue;
            }
            if (stat != null && !overlaps(v.statMin(), v.statMax(), stat.lo(), stat.hi())) {
                continue;
            }
            result.add(v);
        }
        return result;
    }

    private static boolean overlaps(long lo, long hi, long queryLo, long queryHi) {
        return lo <= queryHi && queryLo <= hi;
    }
}
