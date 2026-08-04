package io.ascham.segment;

/**
 * Thrown when the writer tries to open a batch beyond the segment's catalog capacity. It is the
 * signal for the rotation layer to seal off the current segment and continue in a new one (spec: a
 * segment's data region is bounded, and {@code rotate()} fires on capacity exhaustion).
 */
public final class SegmentFullException extends RuntimeException {

    private final int capacity;

    public SegmentFullException(int capacity) {
        super("segment full (" + capacity + " batches); rotation required");
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }
}
