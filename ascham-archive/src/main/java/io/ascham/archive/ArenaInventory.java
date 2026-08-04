package io.ascham.archive;

import io.ascham.read.BatchView;
import io.ascham.read.SnapshotReader;
import io.ascham.rotate.SegmentDirectory;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The arena side of the roll: which roll intervals are pending, whether they are safe to archive,
 * and whether their rows really belong to the interval their file names claim.
 *
 * <p>Reads only — nothing here mutates or deletes. Reclamation is a separate, later step gated on
 * the archive commit being durable (docs/cold-tier-design-plan.md §3, invariant I3).
 */
public final class ArenaInventory {

    private static final System.Logger LOG = System.getLogger(ArenaInventory.class.getName());

    /**
     * One roll unit: the segments of one interval, in the order they must be read (oldest first).
     * Normally the unit is exactly one declared interval; when a writer restarted with a different
     * cycle mid-interval, overlapping intervals are merged into one unit so it still commits
     * atomically. A cycle divides 24h and intervals are midnight-anchored, so a unit never spans
     * two UTC days.
     */
    public record IntervalSegments(Instant start, Instant end, List<SegmentDirectory.SegmentName> segments) {
        /** The UTC day the whole unit lies in. */
        public LocalDate day() {
            return LocalDate.ofInstant(start, ZoneOffset.UTC);
        }

        public List<Path> paths() {
            return segments.stream().map(SegmentDirectory.SegmentName::path).toList();
        }

        public List<String> fileNames() {
            return segments.stream().map(s -> s.path().getFileName().toString()).toList();
        }
    }

    private ArenaInventory() {
    }

    /**
     * Completed roll units — intervals whose declared end is at or before {@code now} — oldest
     * first.
     *
     * <p>The live interval is never a candidate: the writer is still filling it, and any segment it
     * overlaps drags its whole merged unit past {@code now}. Units are returned even if not yet
     * frozen — {@link #isFrozen} is the separate check, because "not frozen yet" is a retry, not an
     * error.
     */
    public static List<IntervalSegments> pendingIntervals(SegmentDirectory dir, Instant now) {
        List<IntervalSegments> out = new ArrayList<>();
        Instant start = null;
        Instant end = null;
        List<SegmentDirectory.SegmentName> unit = new ArrayList<>();
        for (SegmentDirectory.SegmentName seg : dir.list()) { // sorted by (start, cycle, seq)
            if (start != null && seg.start().compareTo(end) < 0) {
                // Overlapping declared intervals (a cycle change mid-interval): one atomic unit.
                end = seg.end().isAfter(end) ? seg.end() : end;
            } else {
                if (start != null) {
                    out.add(new IntervalSegments(start, end, List.copyOf(unit)));
                }
                start = seg.start();
                end = seg.end();
                unit.clear();
            }
            unit.add(seg);
        }
        if (start != null) {
            out.add(new IntervalSegments(start, end, List.copyOf(unit)));
        }
        return out.stream().filter(u -> !u.end().isAfter(now)).toList();
    }

    /**
     * Whether every segment of {@code unit} is guaranteed to receive no further appends.
     *
     * <p>The strong signal is ordering: a writer only ever appends to a segment of the interval it
     * is in, so a segment starting at or after the unit's end means the writer has moved past it.
     * That is the normal case — the writer rotates at the interval boundary even when idle (arena's
     * rotate-on-heartbeat).
     *
     * <p>Otherwise this unit owns the newest segments, which for a completed interval means the
     * writer never rotated: it most likely died. That is only safe to archive once the heartbeat
     * has stopped, so fall back to watching the unit's segments. All of them are probed over one
     * shared window — after a cycle change the live segment need not be the last one in name order,
     * so probing only the name-newest could mistake a live writer for a dead one. The heartbeat is
     * a counter, not a timestamp, so liveness needs two samples separated in time.
     */
    public static boolean isFrozen(SegmentDirectory dir, IntervalSegments unit, Duration livenessProbe) {
        List<SegmentDirectory.SegmentName> all = dir.list();
        if (all.isEmpty()) {
            return false;
        }
        SegmentDirectory.SegmentName newest = all.get(all.size() - 1);
        if (!newest.start().isBefore(unit.end())) {
            return true;
        }
        LOG.log(System.Logger.Level.INFO,
                "interval [{0}, {1}) still owns the newest segment {2}; probing writer heartbeats for {3}",
                unit.start(), unit.end(), newest.path().getFileName(), livenessProbe);
        return writersAreGone(unit.paths(), livenessProbe);
    }

    /** True if no segment's heartbeat advanced across one shared probe window. */
    private static boolean writersAreGone(List<Path> segments, Duration probe) {
        List<SnapshotReader> readers = new ArrayList<>();
        try {
            for (Path segment : segments) {
                readers.add(SnapshotReader.open(segment));
            }
            long[] before = new long[readers.size()];
            for (int i = 0; i < readers.size(); i++) {
                before[i] = readers.get(i).heartbeat();
            }
            Thread.sleep(probe.toMillis());
            for (int i = 0; i < readers.size(); i++) {
                if (readers.get(i).heartbeat() != before[i]) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false; // interrupted: claim nothing, let the next run decide
        } catch (UncheckedIOException e) {
            // A segment vanished under us (a raced unlink). Nothing to archive, nothing to claim.
            return false;
        } finally {
            readers.forEach(SnapshotReader::close);
        }
    }

    /**
     * Verifies invariant I2: every row in the unit's segments carries a timestamp inside the
     * interval its own file name declares.
     *
     * <p>Checked from the batch zone maps, so it costs a header read per segment rather than a
     * scan. A violation aborts the unit rather than splitting or truncating it: if file interval
     * and event time disagree, the day partition and the cutover watermark would disagree too, and
     * the union of realtime and historical data would silently gain or lose rows (§3.1, hole 2).
     *
     * <p>Zero-row batches are skipped — they carry no timestamps, and an empty trailing batch is
     * deliberately left unsealed by the writer.
     *
     * @throws IntervalAlignmentException if any batch's range escapes its segment's interval, or is
     *                                    unsealed
     */
    public static void verifyIntervalAlignment(IntervalSegments unit) {
        for (SegmentDirectory.SegmentName segment : unit.segments()) {
            long intervalStart = toNanos(segment.start());
            long intervalEnd = toNanos(segment.end());
            try (SnapshotReader reader = SnapshotReader.open(segment.path())) {
                List<BatchView> batches = reader.snapshot().batches();
                for (int i = 0; i < batches.size(); i++) {
                    BatchView batch = batches.get(i);
                    if (batch.rowCount() == 0) {
                        continue;
                    }
                    if (!batch.sealed()) {
                        throw new IntervalAlignmentException(segment.path(), i,
                                "batch is still in progress, so its time range is unpublished — the "
                                        + "segment is not finished (a writer may still be appending)");
                    }
                    if (batch.tsMin() < intervalStart || batch.tsMax() >= intervalEnd) {
                        throw new IntervalAlignmentException(segment.path(), i,
                                "time range [" + batch.tsMin() + ", " + batch.tsMax() + "] escapes the "
                                        + "declared interval [" + intervalStart + ", " + intervalEnd + ")");
                    }
                }
            }
        }
    }

    private static long toNanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L;
    }

    /** A segment contains rows outside the interval its name declares, so it cannot be rolled. */
    public static final class IntervalAlignmentException extends RuntimeException {
        public IntervalAlignmentException(Path segment, int batch, String detail) {
            super("interval-alignment violation in " + segment + " batch " + batch + ": " + detail);
        }
    }
}
