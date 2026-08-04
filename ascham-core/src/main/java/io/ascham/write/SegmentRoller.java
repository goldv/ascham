package io.ascham.write;

/**
 * Segment-succession callback for {@link RollingAppender}: supplies the live segment and performs
 * rotations on its behalf. Implemented by {@link io.ascham.rotate.RotatingWriter}, which owns
 * all rotation state (day, sequence, retention); only {@link SegmentWriter}s cross this seam.
 */
public interface SegmentRoller {

    /** The live segment. */
    SegmentWriter current();

    /** True when the time policy wants a new segment. Must be allocation-free in steady state. */
    boolean rotationDue();

    /** Row-closed rotation: seals and closes the current segment, creates and returns its successor. */
    SegmentWriter rotate();

    /** Mid-row rotation, step 1: creates and returns the successor without closing the current segment. */
    SegmentWriter openSuccessor();

    /** Mid-row rotation, step 2: seals and closes the retired segment, applies retention. */
    void retire(SegmentWriter old);
}
