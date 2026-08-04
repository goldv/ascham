package io.ascham.conformance;

import org.agrona.concurrent.EpochNanoClock;

/** Deterministic clock so a case's {@code seal_nanos} — and therefore its bytes — are reproducible. */
final class FixedClock implements EpochNanoClock {

    private final long start;
    private final long step;
    private long calls;

    FixedClock(long start, long step) {
        this.start = start;
        this.step = step;
    }

    @Override
    public long nanoTime() {
        long t = start + step * calls;
        calls++;
        return t;
    }
}
