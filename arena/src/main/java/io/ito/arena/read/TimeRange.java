package io.ito.arena.read;

/**
 * Inclusive query range over the {@code arena.time_column}, for {@link Snapshot#prune}. Both bounds
 * are in the column's declared unit (nanos or micros since epoch).
 */
public record TimeRange(long lo, long hi) {
}
