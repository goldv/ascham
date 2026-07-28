package io.ito.arena.rotate;

import java.time.LocalDate;

/**
 * Decides when to start a new segment for time-based reasons. Capacity-based rotation is handled
 * automatically by {@link RotatingWriter} (it catches {@code SegmentFullException}); a policy only
 * expresses the time/size intent the writer can't infer from a full segment.
 */
public interface RotationPolicy {

    boolean shouldRotate(Context context);

    /** @param currentDay the UTC day of the active segment; @param today the UTC day now */
    record Context(LocalDate currentDay, LocalDate today) {
    }
}
