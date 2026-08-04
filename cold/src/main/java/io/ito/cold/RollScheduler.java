package io.ito.cold;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runs {@link RollService#runOnce()} on a schedule: once at startup to drain any backlog, then
 * either daily at the configured UTC time or every fixed cadence, with backoff retries while
 * anything is still failing. Pick a cadence of roughly the shortest writer roll cycle when tables
 * roll sub-day intervals — a pass with nothing pending is near-free, so erring frequent is cheap.
 *
 * <p>Deliberately thin. Because a pass is idempotent and re-derives its own work, the schedule
 * cannot cause incorrectness — only latency. A missed run, an overlapping run, or a run during a
 * catalog outage all resolve by running again, so this class needs no persistence, no leader
 * election, and no notion of "the run that should have happened at 00:15".
 *
 * <p>Single-threaded: passes never overlap.
 */
public final class RollScheduler implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RollScheduler.class.getName());

    private final Supplier<RollService.Pass> pass;
    private final LocalTime dailyAt; // null when a fixed cadence drives the schedule
    private final Duration cadence;  // null when the daily time drives the schedule
    private final Duration retryFloor;
    private final Duration retryCeiling;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final AtomicReference<RollService.Pass> lastPass = new AtomicReference<>();

    private Duration retryDelay;

    public RollScheduler(RollService service, LocalTime dailyAtUtc, Clock clock) {
        this(service::runOnce, dailyAtUtc, null, Duration.ofSeconds(30), Duration.ofMinutes(15), clock);
    }

    /** Runs a pass every {@code cadence} instead of daily — for arenas rolling sub-day cycles. */
    public RollScheduler(RollService service, Duration cadence, Clock clock) {
        this(service::runOnce, null, cadence, Duration.ofSeconds(30), Duration.ofMinutes(15), clock);
    }

    /**
     * Takes the pass as a supplier rather than the service itself: scheduling only needs "run one
     * pass, tell me whether anything failed", and keeping that boundary narrow means the retry
     * policy can be exercised without a catalog or an arena behind it.
     */
    public RollScheduler(Supplier<RollService.Pass> pass, LocalTime dailyAtUtc, Duration retryFloor,
                         Duration retryCeiling, Clock clock) {
        this(pass, dailyAtUtc, null, retryFloor, retryCeiling, clock);
    }

    /** The supplier form of the fixed-cadence schedule. */
    public RollScheduler(Supplier<RollService.Pass> pass, Duration cadence, Duration retryFloor,
                         Duration retryCeiling, Clock clock) {
        this(pass, null, cadence, retryFloor, retryCeiling, clock);
    }

    private RollScheduler(Supplier<RollService.Pass> pass, LocalTime dailyAtUtc, Duration cadence,
                          Duration retryFloor, Duration retryCeiling, Clock clock) {
        if ((dailyAtUtc == null) == (cadence == null)) {
            throw new IllegalArgumentException("exactly one of dailyAtUtc and cadence must be set");
        }
        this.pass = pass;
        this.dailyAt = dailyAtUtc;
        this.cadence = cadence;
        this.retryFloor = retryFloor;
        this.retryCeiling = retryCeiling;
        this.retryDelay = retryFloor;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cold-roll");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts the loop with an immediate backlog-draining pass. */
    public void start() {
        executor.execute(this::runPass);
    }

    /** The most recent completed pass, if any. */
    public RollService.Pass lastPass() {
        return lastPass.get();
    }

    private void runPass() {
        RollService.Pass result;
        try {
            result = pass.get();
            lastPass.set(result);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "roll pass failed outright", e);
            scheduleRetry();
            return;
        }
        if (result.failures().isEmpty()) {
            retryDelay = retryFloor; // recovered: forget the backoff
            scheduleNext();
        } else {
            // Something is still pending — most often the catalog being unreachable, or a day whose
            // writer has not let go yet. Retry sooner than tomorrow, backing off so an extended
            // outage does not turn into a hot loop against a dead endpoint.
            LOG.log(System.Logger.Level.WARNING, "{0} table(s) failed; retrying in {1}",
                    result.failures().size(), retryDelay);
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        executor.schedule(this::runPass, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
        retryDelay = min(retryDelay.multipliedBy(2), retryCeiling);
    }

    private void scheduleNext() {
        long millis = cadence != null ? cadence.toMillis() : untilNextDaily().toMillis();
        LOG.log(System.Logger.Level.DEBUG, "next roll pass in {0} ms", millis);
        executor.schedule(this::runPass, millis, TimeUnit.MILLISECONDS);
    }

    /** Time until the next occurrence of the configured UTC time-of-day. */
    Duration untilNextDaily() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime next = now.toLocalDate().atTime(dailyAt).atZone(ZoneOffset.UTC);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
