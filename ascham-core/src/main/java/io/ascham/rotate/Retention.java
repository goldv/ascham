package io.ascham.rotate;

/**
 * When {@link RotatingWriter} may unlink its own old segments.
 *
 * <p><b>The default is {@link #none()}, and that is a correctness position, not a convenience.</b>
 * Count-based eviction deletes the oldest segments knowing nothing about whether their rows have
 * been archived, so with a cold tier in the picture it can silently destroy data that was never
 * written down. Reclamation belongs to whoever knows what has been persisted — the roll service,
 * which unlinks only after the archive commit is durable (see {@code docs/cold-tier-design-plan.md}).
 *
 * <p>{@link #emergencyBackstop(int)} keeps the old behaviour available for deployments with no
 * archiver, or as a last line of defence against exhausting {@code /dev/shm} when the archiver has
 * been down for a long time. It is not a normal operating mode: every eviction it performs is
 * logged at ERROR, because in an archived deployment it firing at all means the archiver has fallen
 * far enough behind that data is now being dropped.
 */
public final class Retention {

    private static final Retention NONE = new Retention(0);

    private final int maxSegments;

    private Retention(int maxSegments) {
        this.maxSegments = maxSegments;
    }

    /** The writer never unlinks; segment reclamation is someone else's job. The default. */
    public static Retention none() {
        return NONE;
    }

    /**
     * Keep at most {@code maxSegments} segments in the table directory, unlinking oldest-first on
     * rotation. Every eviction logs at ERROR — see the class note on why this is not a normal mode.
     *
     * @param maxSegments maximum segments to retain; must be positive
     */
    public static Retention emergencyBackstop(int maxSegments) {
        if (maxSegments <= 0) {
            throw new IllegalArgumentException(
                    "backstop retention must keep at least one segment: " + maxSegments
                            + " (use Retention.none() to disable writer-side reclamation)");
        }
        return new Retention(maxSegments);
    }

    public boolean enabled() {
        return maxSegments > 0;
    }

    /** Maximum segments to keep; meaningful only when {@link #enabled()}. */
    public int maxSegments() {
        return maxSegments;
    }

    @Override
    public String toString() {
        return enabled() ? "Retention.emergencyBackstop(" + maxSegments + ")" : "Retention.none()";
    }
}
