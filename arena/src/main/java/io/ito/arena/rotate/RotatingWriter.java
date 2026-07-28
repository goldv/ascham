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
 * or on capacity exhaustion, and applies retention by unlinking the oldest segments. Rotation is
 * transparent to the producer — {@link #append} always writes into a live segment.
 *
 * <p>Rotating closes only the writer's own mapping of the old segment; readers hold independent
 * mappings and are unaffected, and the old segment's file stays on disk until retention unlinks it
 * (after which mapped readers keep reading via the kernel refcount — spec M5).
 */
public final class RotatingWriter implements AutoCloseable {

    private final SegmentDirectory directory;
    private final ArenaSchema schema;
    private final int maxBatches;
    private final long epoch;
    private final int retention;
    private final RotationPolicy policy;
    private final Clock clock;
    private final EpochNanoClock nanoClock;

    private SegmentWriter current;
    private LocalDate currentDay;
    private int currentSeq;

    private RotatingWriter(SegmentDirectory directory, ArenaSchema schema, int maxBatches, long epoch,
                           int retention, RotationPolicy policy, Clock clock, EpochNanoClock nanoClock) {
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
     * Opens (or resumes) a table for writing. The first segment is created for the current UTC day
     * at the next free sequence number. On restart, pass a higher {@code epoch} (e.g.
     * {@code directory.latestEpoch()+1}) so readers can detect the new writer instance.
     */
    public static RotatingWriter open(SegmentDirectory directory, ArenaSchema schema, int maxBatches,
                                      long epoch, int retention, RotationPolicy policy,
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

    /** Advances the current segment's liveness heartbeat. */
    public void heartbeat() {
        current.heartbeat();
    }

    public SegmentWriter current() {
        return current;
    }

    public Path currentPath() {
        return directory.segmentPath(currentDay, currentSeq);
    }

    @Override
    public void close() {
        current.close();
    }

    private void rotate(LocalDate today) {
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
        List<SegmentDirectory.SegmentName> segments = directory.list();
        int evict = segments.size() - retention;
        for (int i = 0; i < evict; i++) {
            directory.unlink(segments.get(i).path()); // oldest first
        }
    }

    private static LocalDate today(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
