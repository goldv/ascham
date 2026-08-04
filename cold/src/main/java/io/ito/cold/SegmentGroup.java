package io.ito.cold;

import io.ito.arena.read.BatchView;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * One roll file group: N consecutive segments of the same day, opened as zero-copy Arrow roots.
 * This is the random-access substrate the index sort permutes over — a global row index
 * {@code [0, rowCount)} resolves to a (batch root, row-in-batch) pair via binary search on the
 * batches' start offsets, and the underlying bytes stay in the mmap'd segments throughout.
 *
 * <p>Only sealed batches are included: the roll runs strictly after
 * {@link ArenaInventory#verifyIntervalAlignment}, which already rejects in-progress batches, so an
 * unsealed batch here means the protocol was bypassed and is a hard error. Zero-row batches are
 * skipped, matching that check's treatment.
 */
final class SegmentGroup implements AutoCloseable {

    private final List<SnapshotReader> readers;
    private final List<VectorSchemaRoot> roots;
    private final int[] starts; // starts[b] = global row index of roots[b]'s first row
    private final int rowCount;
    private final ArenaSchema schema;

    private SegmentGroup(List<SnapshotReader> readers, List<VectorSchemaRoot> roots,
                         int[] starts, int rowCount, ArenaSchema schema) {
        this.readers = readers;
        this.roots = roots;
        this.starts = starts;
        this.rowCount = rowCount;
        this.schema = schema;
    }

    static SegmentGroup open(List<Path> segments) {
        List<SnapshotReader> readers = new ArrayList<>(segments.size());
        List<VectorSchemaRoot> roots = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        long total = 0;
        try {
            ArenaSchema schema = null;
            for (Path segment : segments) {
                SnapshotReader reader = SnapshotReader.open(segment);
                readers.add(reader);
                if (schema == null) {
                    schema = reader.schema();
                }
                for (BatchView batch : reader.snapshot().batches()) {
                    if (batch.rowCount() == 0) {
                        continue;
                    }
                    if (!batch.sealed()) {
                        throw new ColdException("segment " + segment + " batch " + batch.batchIndex()
                                + " is not sealed — a file group must only be opened after "
                                + "day-alignment verification");
                    }
                    starts.add(Math.toIntExact(total));
                    total += batch.rowCount();
                    // A group is bounded by maxSegmentsPerFile × segment capacity, far below 2^31 rows;
                    // the guard keeps a misconfiguration from overflowing the index silently.
                    Math.toIntExact(total);
                    roots.add(batch.root());
                }
            }
            int[] startArr = new int[starts.size()];
            for (int i = 0; i < startArr.length; i++) {
                startArr[i] = starts.get(i);
            }
            return new SegmentGroup(readers, roots, startArr, Math.toIntExact(total), schema);
        } catch (RuntimeException e) {
            closeAll(roots, readers);
            throw e;
        }
    }

    ArenaSchema schema() {
        return schema;
    }

    int rowCount() {
        return rowCount;
    }

    int batchCount() {
        return roots.size();
    }

    VectorSchemaRoot root(int batch) {
        return roots.get(batch);
    }

    int batchStart(int batch) {
        return starts[batch];
    }

    /** The batch holding {@code globalRow}: greatest b with starts[b] <= globalRow. */
    int batchOf(int globalRow) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= globalRow) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    @Override
    public void close() {
        closeAll(roots, readers);
    }

    private static void closeAll(List<VectorSchemaRoot> roots, List<SnapshotReader> readers) {
        // Roots wrap NO_OP buffers over the mmap, so closing them releases only vector metadata;
        // the mappings themselves are released with the readers.
        roots.forEach(VectorSchemaRoot::close);
        readers.forEach(SnapshotReader::close);
    }
}
