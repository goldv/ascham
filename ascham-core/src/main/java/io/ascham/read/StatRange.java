package io.ascham.read;

/** Inclusive query range over the {@code ascham.stats_column}, for {@link Snapshot#prune}. */
public record StatRange(long lo, long hi) {
}
