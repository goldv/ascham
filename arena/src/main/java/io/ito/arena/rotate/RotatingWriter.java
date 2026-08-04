package io.ito.arena.rotate;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.write.Appender;
import io.ito.arena.write.RollingAppender;
import io.ito.arena.write.SegmentRoller;
import io.ito.arena.write.SegmentWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Drives a table's segment lifecycle: rows go through {@link #appender()}, which rotates on the
 * roll-cycle boundary (time) or on capacity exhaustion; only if a {@link Retention} backstop is
 * configured are the oldest segments unlinked. Rotation is transparent to the producer: the
 * appender always writes into a live segment, exception-free, even when a row's varlen bytes
 * overflow the last batch of a segment (the partially-written row is adopted into the successor).
 *
 * <p>Rotating closes only the writer's own mapping of the old segment, which seals its trailing
 * batch so the segment is left fully self-describing. Readers hold independent mappings and are
 * unaffected, and the file stays on disk until something unlinks it (after which mapped readers keep
 * reading via the kernel refcount — spec M5). By default that "something" is not this class: see
 * {@link Retention}.
 *
 * <p>During a mid-row rotation the old and new segments are briefly mapped simultaneously — a
 * transient spike of one segment's size in shared memory.
 */
public final class RotatingWriter implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RotatingWriter.class.getName());

    private final SegmentDirectory directory;
    private final ArenaSchema schema;
    private final int maxBatches;
    private final long epoch;
    private final Retention retention;
    private final RollCycle cycle;
    private final Clock clock;
    private final EpochNanoClock nanoClock;

    private SegmentWriter current;
    private Instant currentIntervalStart;
    private int currentSeq;
    private RollingAppender appender;

    // Amortizes the per-row rotation check to zero allocation: one long comparison against the
    // interval-end boundary, recomputed only on rotation.
    private long nextBoundaryMillis;

    private RotatingWriter(SegmentDirectory directory, ArenaSchema schema, int maxBatches, long epoch,
                           Retention retention, RollCycle cycle, Clock clock, EpochNanoClock nanoClock) {
        this.directory = directory;
        this.schema = schema;
        this.maxBatches = maxBatches;
        this.epoch = epoch;
        this.retention = retention;
        this.cycle = cycle;
        this.clock = clock;
        this.nanoClock = nanoClock;
    }

    /**
     * Opens (or resumes) a table for writing, with no writer-side segment reclamation
     * ({@link Retention#none()}) — the default; see {@link Retention} for why.
     */
    public static RotatingWriter open(SegmentDirectory directory, ArenaSchema schema, int maxBatches,
                                      long epoch, RollCycle cycle,
                                      Clock clock, EpochNanoClock nanoClock) {
        return open(directory, schema, maxBatches, epoch, Retention.none(), cycle, clock, nanoClock);
    }

    /**
     * Opens (or resumes) a table for writing. The first segment is created for the current roll
     * interval at the next free sequence number. On restart, pass a higher {@code epoch} (e.g.
     * {@code directory.latestEpoch()+1}) so readers can detect the new writer instance.
     */
    public static RotatingWriter open(SegmentDirectory directory, ArenaSchema schema, int maxBatches,
                                      long epoch, Retention retention, RollCycle cycle,
                                      Clock clock, EpochNanoClock nanoClock) {
        RotatingWriter w = new RotatingWriter(directory, schema, maxBatches, epoch, retention, cycle, clock, nanoClock);
        w.currentIntervalStart = cycle.floor(clock.instant());
        w.currentSeq = directory.nextSeq(w.currentIntervalStart, cycle);
        w.current = w.createSegment();
        w.refreshBoundary();
        w.appender = new RollingAppender(w.new Roller());
        w.applyRetention();
        return w;
    }

    /** The table's single long-lived appender. Rotation happens inside it; it never throws full. */
    public Appender appender() {
        return appender;
    }

    /**
     * Forces rotation to a new segment in the current roll interval.
     *
     * @throws IllegalStateException if a row is open — forced rotation mid-row has no sane
     *                               semantics (open-row adoption is reserved for capacity-forced rotation)
     */
    public void rotate() {
        if (appender.rowOpen()) {
            throw new IllegalStateException("cannot force rotation mid-row; call endRow() first");
        }
        rotateNow();
    }

    /**
     * Advances the current segment's liveness heartbeat, rotating first if the roll interval has
     * ended.
     *
     * <p>The rotation check matters for quiet tables: rotation is otherwise only evaluated at
     * {@code beginRow}, so a writer that is alive but idle across the interval boundary would keep
     * the previous interval's segment open indefinitely. An archiver cannot roll that segment (a
     * writer may still append to it), so the interval's data would sit unarchived until the next
     * row arrived. Heartbeating on a timer — which a live writer must do anyway — closes that
     * window.
     *
     * <p>If a row is open, rotation is deferred (the heartbeat still bumps): the boundary fires at
     * the next {@code beginRow} instead. A quiet table has no open row, so the window above stays
     * closed.
     */
    public void heartbeat() {
        if (!appender.rowOpen() && clock.millis() >= nextBoundaryMillis) {
            rotateNow();
        }
        current.heartbeat();
    }

    public SegmentWriter current() {
        return current;
    }

    public Path currentPath() {
        return directory.segmentPath(currentIntervalStart, cycle, currentSeq);
    }

    /**
     * Seals the trailing batch of the current segment, then releases it. An open (begun, never
     * ended) row is discarded — it was never published, so this is indistinguishable from the
     * writer crashing between {@code beginRow} and {@code endRow}; producers that care must
     * {@code endRow()} first. Never throws for that (close runs in finally blocks).
     */
    @Override
    public void close() {
        if (appender.rowOpen()) {
            LOG.log(System.Logger.Level.WARNING, "closing with an open row; the row was never published and is dropped");
        }
        current.sealFinal(); // graceful shutdown: no more rows are coming, so publish the last stats
        current.close();
    }

    /** The appender's window into rotation. All rotation state stays in RotatingWriter. */
    private final class Roller implements SegmentRoller {
        @Override
        public SegmentWriter current() {
            return current;
        }

        @Override
        public boolean rotationDue() {
            return clock.millis() >= nextBoundaryMillis;
        }

        @Override
        public SegmentWriter rotate() {
            rotateNow();
            return current;
        }

        @Override
        public SegmentWriter openSuccessor() {
            return RotatingWriter.this.openSuccessor();
        }

        @Override
        public void retire(SegmentWriter old) {
            retireSegment(old);
            applyRetention();
        }
    }

    private void rotateNow() {
        retireSegment(current);
        openSuccessor();
        applyRetention();
    }

    private void retireSegment(SegmentWriter old) {
        // Seal before closing: this segment will never be appended to again, so its last batch must
        // carry published stats rather than looking forever like a batch still being written.
        old.sealFinal();
        old.close(); // writer done with this segment; readers keep their own mappings
    }

    private SegmentWriter openSuccessor() {
        Instant nowStart = cycle.floor(clock.instant());
        Instant start;
        int seq;
        if (nowStart.equals(currentIntervalStart)) {
            start = currentIntervalStart; // capacity (or forced) rotation stays inside the interval
            seq = currentSeq + 1;
        } else {
            start = nowStart;
            seq = directory.nextSeq(nowStart, cycle);
        }
        // Bookkeeping commits only after the successor exists: if creation throws (disk/shm full),
        // the current segment — and any open row in it — is untouched and the producer can retry.
        SegmentWriter next = SegmentWriter.createSegment(
                directory.segmentPath(start, cycle, seq), schema, maxBatches, epoch, seq, nanoClock);
        current = next;
        currentIntervalStart = start;
        currentSeq = seq;
        refreshBoundary();
        return next;
    }

    private SegmentWriter createSegment() {
        return SegmentWriter.createSegment(
                directory.segmentPath(currentIntervalStart, cycle, currentSeq), schema, maxBatches, epoch,
                currentSeq, nanoClock);
    }

    private void refreshBoundary() {
        nextBoundaryMillis = cycle.end(currentIntervalStart).toEpochMilli();
    }

    private void applyRetention() {
        if (!retention.enabled()) {
            return;
        }
        List<SegmentDirectory.SegmentName> segments = directory.list();
        int evict = segments.size() - retention.maxSegments();
        for (int i = 0; i < evict; i++) {
            Path victim = segments.get(i).path(); // oldest first
            // ERROR, not DEBUG: in a deployment with an archiver this is data loss — the backstop
            // only reaches a segment the archiver has not yet reclaimed. See Retention.
            LOG.log(System.Logger.Level.ERROR,
                    "retention backstop evicting segment {0} (keeping {1}); if a cold-tier roll owns "
                            + "reclamation, this is UNARCHIVED DATA BEING DROPPED — the archiver is behind",
                    victim, retention.maxSegments());
            directory.unlink(victim);
        }
    }
}
