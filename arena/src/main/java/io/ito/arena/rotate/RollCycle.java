package io.ito.arena.rotate;

import java.time.Duration;
import java.time.Instant;

/**
 * The duration of one roll interval — how much wall-clock time a run of segments covers before the
 * writer starts a new interval, and the atomic unit the cold tier commits to the historical store.
 *
 * <p>A cycle must divide 24h evenly (4h, 6h, 8h, 12h, 1d, ...) so every interval is a
 * midnight-anchored slice of a single UTC day: intervals never cross a day boundary, which keeps
 * daily partitioning in the historical store well defined whatever the cycle.
 */
public record RollCycle(int minutes) {

    private static final int MINUTES_PER_DAY = 24 * 60;

    /** One segment interval per UTC day (the v1 default). */
    public static final RollCycle DAILY = new RollCycle(MINUTES_PER_DAY);

    public RollCycle {
        if (minutes < 1 || MINUTES_PER_DAY % minutes != 0) {
            throw new IllegalArgumentException(
                    "roll cycle must be a whole number of minutes dividing 24h evenly, got " + minutes + "m");
        }
    }

    public static RollCycle of(Duration duration) {
        if (duration.isZero() || duration.isNegative() || duration.toNanos() % 60_000_000_000L != 0) {
            throw new IllegalArgumentException("roll cycle must be a positive whole number of minutes, got " + duration);
        }
        long minutes = duration.toMinutes();
        if (minutes > MINUTES_PER_DAY) {
            throw new IllegalArgumentException("roll cycle cannot exceed 1d, got " + duration);
        }
        return new RollCycle((int) minutes);
    }

    /** Parses {@code "30m"}, {@code "4h"} or {@code "1d"}. */
    public static RollCycle parse(String value) {
        String s = value.trim();
        if (s.length() < 2) {
            throw new IllegalArgumentException("cannot parse roll cycle '" + value + "' (want e.g. 30m, 4h, 1d)");
        }
        long amount = Long.parseLong(s.substring(0, s.length() - 1));
        return of(switch (s.charAt(s.length() - 1)) {
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException(
                    "cannot parse roll cycle '" + value + "' (want e.g. 30m, 4h, 1d)");
        });
    }

    public Duration duration() {
        return Duration.ofMinutes(minutes);
    }

    public boolean daily() {
        return minutes == MINUTES_PER_DAY;
    }

    /** The start of the interval containing {@code t} — UTC, midnight-anchored. */
    public Instant floor(Instant t) {
        long cycleMillis = minutes * 60_000L;
        return Instant.ofEpochMilli(Math.floorDiv(t.toEpochMilli(), cycleMillis) * cycleMillis);
    }

    /** The exclusive end of the interval starting at {@code intervalStart}. */
    public Instant end(Instant intervalStart) {
        return intervalStart.plus(duration());
    }
}
