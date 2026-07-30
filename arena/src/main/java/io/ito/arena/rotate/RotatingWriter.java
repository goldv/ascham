package io.ito.arena.rotate;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.segment.SegmentFullException;
import io.ito.arena.write.GenericAppender;
import io.ito.arena.write.SegmentWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Drives a table's segment lifecycle: appends into the current segment, rotates on the policy (time)
 * or on capacity exhaustion, and — only if a {@link Retention} backstop is configured — unlinks the
 * oldest segments. Rotation is transparent to the producer: {@link #append} always writes into a
 * live segment.
 *
 * <p>Rotating closes only the writer's own mapping of the old segment, which seals its trailing
 * batch so the segment is left fully self-describing. Readers hold independent mappings and are
 * unaffected, and the file stays on disk until something unlinks it (after which mapped readers keep
 * reading via the kernel refcount — spec M5). By default that "something" is not this class: see
 * {@link Retention}.
 */
public final class RotatingWriter implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RotatingWriter.class.getName());

    private final SegmentDirectory directory;
    private final ArenaSchema schema;
    private final int maxBatches;
    private final long epoch;
    private final Retention retention;
    private final RotationPolicy policy;
    private final Clock clock;
    private final EpochNanoClock nanoClock;

    private SegmentWriter current;
    private LocalDate currentDay;
    private int currentSeq;

    private RotatingWriter(SegmentDirectory directory, ArenaSchema schema, int maxBatches, long epoch,
                           Retention retention, RotationPolicy policy, Clock clock, EpochNanoClock nanoClock) {
        this.directory = directory;
        this.schema = schema;
        this.maxBatches = maxBatches;
        this.epoch = epoch;
        this.retention = retention;
        this.policy = policy;
        this.clock = clock;
        this.nanoClock = nanoClock;
    }

    /**
     * Opens (or resumes) a table for writing, with no writer-side segment reclamation
     * ({@link Retention#none()}) — the default; see {@link Retention} for why.
     */
    public static RotatingWriter open(SegmentDirectory directory, ArenaSchema schema, int maxBatches,
                                      long epoch, RotationPolicy policy,
                                      Clock clock, EpochNanoClock nanoClock) {
        return open(directory, schema, maxBatches, epoch, Retention.none(), policy, clock, nanoClock);
    }

    /**
     * Opens (or resumes) a table for writing. The first segment is created for the current UTC day
     * at the next free sequence number. On restart, pass a higher {@code epoch} (e.g.
     * {@code directory.latestEpoch()+1}) so readers can detect the new writer instance.
     */
    public static RotatingWriter open(SegmentDirectory directory, ArenaSchema schema, int maxBatches,
                                      long epoch, Retention retention, RotationPolicy policy,
                                      Clock clock, EpochNanoClock nanoClock) {
        RotatingWriter w = new RotatingWriter(directory, schema, maxBatches, epoch, retention, policy, clock, nanoClock);
        w.currentDay = today(clock);
        w.currentSeq = directory.nextSeq(w.currentDay);
        w.current = w.createSegment();
        w.applyRetention();
        return w;
    }

    /**
     * Appends one row, rotating first if the policy fires or the segment is full. The row lambda must
     * write exactly one row ({@code beginRow()} … setters … {@code endRow()}); it may be re-invoked
     * on a fresh segment if the current one fills, so it must not have external side effects.
     */
    public void append(Consumer<GenericAppender> row) {
        LocalDate today = today(clock);
        if (policy.shouldRotate(new RotationPolicy.Context(currentDay, today))) {
            rotate(today);
        }
        try {
            row.accept(current.genericAppender());
        } catch (SegmentFullException full) {
            rotate(today);
            row.accept(current.genericAppender());
        }
    }

    /** Forces rotation to a new segment on the current day. */
    public void rotate() {
        rotate(today(clock));
    }

    /**
     * Advances the current segment's liveness heartbeat, rotating first if the policy has come due.
     *
     * <p>The rotation check matters for quiet tables: rotation is otherwise only evaluated inside
     * {@link #append}, so a writer that is alive but idle across the day boundary would keep
     * yesterday's segment open indefinitely. An archiver cannot roll that segment (a writer may
     * still append to it), so yesterday's data would sit unarchived until the next row arrived.
     * Heartbeating on a timer — which a live writer must do anyway — closes that window.
     */
    public void heartbeat() {
        LocalDate today = today(clock);
        if (policy.shouldRotate(new RotationPolicy.Context(currentDay, today))) {
            rotate(today);
        }
        current.heartbeat();
    }

    public SegmentWriter current() {
        return current;
    }

    public Path currentPath() {
        return directory.segmentPath(currentDay, currentSeq);
    }

    /** Seals the trailing batch of the current segment, then releases it. */
    @Override
    public void close() {
        current.sealFinal(); // graceful shutdown: no more rows are coming, so publish the last stats
        current.close();
    }

    private void rotate(LocalDate today) {
        // Seal before closing: this segment will never be appended to again, so its last batch must
        // carry published stats rather than looking forever like a batch still being written.
        current.sealFinal();
        current.close(); // writer done with this segment; readers keep their own mappings
        if (today.equals(currentDay)) {
            currentSeq++;
        } else {
            currentDay = today;
            currentSeq = directory.nextSeq(today);
        }
        current = createSegment();
        applyRetention();
    }

    private SegmentWriter createSegment() {
        return SegmentWriter.createSegment(
                directory.segmentPath(currentDay, currentSeq), schema, maxBatches, epoch, currentSeq, nanoClock);
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

    private static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
