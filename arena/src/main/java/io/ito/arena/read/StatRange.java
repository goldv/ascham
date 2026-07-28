package io.ito.arena.read;

/** Inclusive query range over the {@code arena.stats_column}, for {@link Snapshot#prune}. */
public record StatRange(long lo, long hi) {
}
