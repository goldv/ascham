package io.ascham.read;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * Reader-side liveness: distinguishes a quiet writer from a dead one via the header heartbeat, and
 * surfaces the writer epoch so a restart (a new writer instance) is detectable. A well-behaved writer
 * advances the heartbeat periodically even when idle, so a frozen heartbeat past the stall threshold
 * means the writer is gone; a still-advancing heartbeat with an unchanging in-progress row count
 * means the writer is alive but not appending (quiet or stuck).
 */
public final class LivenessMonitor {

    public enum Status {
        /** Heartbeat advanced recently — the writer is alive. */
        ALIVE,
        /** Heartbeat has not advanced within the stall threshold — the writer is likely dead. */
        STALLED
    }

    private final SnapshotReader reader;
    private final long stallThresholdNanos;
    private final LongSupplier nanoTime;
    private long lastHeartbeat;
    private long lastAdvanceNanos;

    public LivenessMonitor(SnapshotReader reader, Duration stallThreshold, LongSupplier nanoTime) {
        this.reader = reader;
        this.stallThresholdNanos = stallThreshold.toNanos();
        this.nanoTime = nanoTime;
        this.lastHeartbeat = reader.heartbeat();
        this.lastAdvanceNanos = nanoTime.getAsLong();
    }

    public long writerEpoch() {
        return reader.writerEpoch();
    }

    public long heartbeat() {
        return reader.heartbeat();
    }

    /** Samples liveness: ALIVE if the heartbeat has advanced within the stall threshold. */
    public Status poll() {
        long heartbeat = reader.heartbeat();
        long now = nanoTime.getAsLong();
        if (heartbeat != lastHeartbeat) {
            lastHeartbeat = heartbeat;
            lastAdvanceNanos = now;
            return Status.ALIVE;
        }
        return now - lastAdvanceNanos >= stallThresholdNanos ? Status.STALLED : Status.ALIVE;
    }

    /**
     * Row count of the in-progress (unsealed) batch, or empty if there is none. Combined with
     * {@link #poll}, a caller detects a stuck writer: heartbeat advancing but this count unchanged.
     */
    public OptionalLong inProgressRowCount() {
        List<BatchView> batches = reader.snapshot().batches();
        if (batches.isEmpty()) {
            return OptionalLong.empty();
        }
        BatchView last = batches.get(batches.size() - 1);
        return last.sealed() ? OptionalLong.empty() : OptionalLong.of(last.rowCount());
    }
}
