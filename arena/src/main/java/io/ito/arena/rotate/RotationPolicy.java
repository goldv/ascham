package io.ito.arena.rotate;

import java.time.LocalDate;

/**
 * Decides when to start a new segment for time-based reasons. Capacity-based rotation is handled
 * automatically by the appender ({@link RotatingWriter#appender()}); a policy only expresses the
 * time/size intent the writer can't infer from a full segment.
 *
 * <p>The policy is invoked for every row, but the {@link Context} it sees is cached: {@code today}
 * is only refreshed when the clock crosses the UTC day boundary (or on rotation), so a policy
 * cannot observe intra-day time through it.
 */
public interface RotationPolicy {

    boolean shouldRotate(Context context);

    /** @param currentDay the UTC day of the active segment; @param today the UTC day now */
    record Context(LocalDate currentDay, LocalDate today) {
    }
}
