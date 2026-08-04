package io.ascham.read;

/**
 * Inclusive query range over the {@code ascham.time_column}, for {@link Snapshot#prune}. Both bounds
 * are in the column's declared unit (nanos or micros since epoch).
 */
public record TimeRange(long lo, long hi) {
}
