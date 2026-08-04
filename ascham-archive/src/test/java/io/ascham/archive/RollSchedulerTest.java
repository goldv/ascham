package io.ascham.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scheduling: startup drain, daily cadence, and backoff while something is still failing. */
class RollSchedulerTest {

    @TempDir
    Path base;

    @Test
    void theNextDailyRunIsTheNextOccurrenceOfTheConfiguredTime() {
        Clock at23 = Clock.fixed(Instant.parse("2026-07-28T23:00:00Z"), ZoneOffset.UTC);
        RollScheduler beforeMidnight = new RollScheduler(service(), LocalTime.of(0, 15), at23);
        // 23:00 -> 00:15 tomorrow is 75 minutes away.
        assertThat(beforeMidnight.untilNextDaily()).isEqualTo(Duration.ofMinutes(75));
        beforeMidnight.close();

        Clock at0020 = Clock.fixed(Instant.parse("2026-07-28T00:20:00Z"), ZoneOffset.UTC);
        RollScheduler justAfter = new RollScheduler(service(), LocalTime.of(0, 15), at0020);
        // Today's slot has passed, so the next one is tomorrow — never a negative or zero delay.
        assertThat(justAfter.untilNextDaily()).isEqualTo(Duration.ofMinutes(24 * 60 - 5));
        justAfter.close();
    }

    @Test
    void aFixedCadenceKeepsRunningPassesWithoutWaitingForTheDailySlot() throws Exception {
        AtomicInteger passes = new AtomicInteger();
        try (RollScheduler scheduler = new RollScheduler(countingPass(passes, false),
                Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(80),
                Clock.systemUTC())) {
            scheduler.start();
            // Clean passes reschedule on the cadence, not tomorrow's daily slot.
            assertThat(waitFor(() -> passes.get() >= 3)).isTrue();
        }
    }

    @Test
    void startupRunsAPassImmediatelyToDrainAnyBacklog() throws Exception {
        AtomicInteger passes = new AtomicInteger();
        try (RollScheduler scheduler = new RollScheduler(countingPass(passes, false), LocalTime.of(0, 15),
                Duration.ofMillis(20), Duration.ofMillis(80), Clock.systemUTC())) {
            scheduler.start();
            assertThat(waitFor(() -> passes.get() >= 1)).isTrue();
            assertThat(scheduler.lastPass()).isNotNull();
        }
    }

    @Test
    void aFailingPassRetriesSoonerThanTomorrow() throws Exception {
        AtomicInteger passes = new AtomicInteger();
        // A short floor so the test does not wait on the production 30s backoff.
        try (RollScheduler scheduler = new RollScheduler(countingPass(passes, true), LocalTime.of(0, 15),
                Duration.ofMillis(20), Duration.ofMillis(80), Clock.systemUTC())) {
            scheduler.start();
            // Retries keep coming while the failure persists, instead of sleeping until the next day.
            assertThat(waitFor(() -> passes.get() >= 3)).isTrue();
            assertThat(scheduler.lastPass().failures()).isNotEmpty();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return false;
    }

    private RollService service() {
        return new RollService(config(), new FakeRollExecutor(), 0);
    }

    /** A pass that counts invocations and can report a persistent table failure. */
    private static java.util.function.Supplier<RollService.Pass> countingPass(AtomicInteger passes,
                                                                              boolean fail) {
        return () -> {
            passes.incrementAndGet();
            if (fail) {
                return new RollService.Pass(java.util.List.of(new RollService.TableOutcome("quotes",
                        new TableRoller.RollResult("quotes", java.util.List.of()),
                        new ArchiveException("catalog unreachable"))), 0);
            }
            return new RollService.Pass(java.util.List.of(), 0);
        };
    }

    private ArchiveConfig config() {
        return ArchiveConfig.builder()
                .arenaBaseDir(base)
                .destination(base.resolve("warehouse").toString())
                .build();
    }
}
