package io.ito.cold;

import io.ito.arena.read.BatchView;
import io.ito.arena.read.SnapshotReader;
import io.ito.arena.rotate.SegmentDirectory;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arena side of the roll: which days are pending, whether they are safe to archive, and whether
 * their rows really belong to the day their file names claim.
 *
 * <p>Reads only — nothing here mutates or deletes. Reclamation is a separate, later step gated on
 * the archive commit being durable (docs/cold-tier-design-plan.md §3, invariant I3).
 */
public final class ArenaInventory {

    private static final System.Logger LOG = System.getLogger(ArenaInventory.class.getName());

    /** One day's segments, in the order they must be read (oldest sequence first). */
    public record DaySegments(LocalDate day, List<Path> segments) {
        public List<String> fileNames() {
            return segments.stream().map(p -> p.getFileName().toString()).toList();
        }
    }

    private ArenaInventory() {
    }

    /**
     * Days strictly before {@code today} that still have segments, oldest first.
     *
     * <p>Today is never a candidate: the writer is still filling it. Days are returned even if not
     * yet frozen — {@link #isFrozen} is the separate check, because "not frozen yet" is a retry, not
     * an error.
     */
    public static List<DaySegments> pendingDays(SegmentDirectory dir, LocalDate today) {
        Map<LocalDate, List<Path>> byDay = new LinkedHashMap<>();
        for (SegmentDirectory.SegmentName seg : dir.list()) { // already sorted by (day, seq)
            if (seg.day().isBefore(today)) {
                byDay.computeIfAbsent(seg.day(), d -> new ArrayList<>()).add(seg.path());
            }
        }
        List<DaySegments> out = new ArrayList<>();
        byDay.forEach((day, segments) -> out.add(new DaySegments(day, List.copyOf(segments))));
        out.sort((a, b) -> a.day().compareTo(b.day()));
        return out;
    }

    public static LocalDate todayUtc() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /**
     * Whether every segment of {@code day} is guaranteed to receive no further appends.
     *
     * <p>The strong signal is ordering: a writer only ever appends to the newest segment in the
     * directory, so if anything newer exists, this day is finished. That is the normal case — the
     * writer rotates at the day boundary even when idle (arena's rotate-on-heartbeat).
     *
     * <p>Otherwise this day owns the newest segment, which for a past day means the writer never
     * rotated: it most likely died. That is only safe to archive once the heartbeat has stopped, so
     * fall back to watching it. The heartbeat is a counter, not a timestamp, so liveness needs two
     * samples separated in time — a single read cannot tell a dead writer from a quiet one.
     */
    public static boolean isFrozen(SegmentDirectory dir, DaySegments day, Duration livenessProbe) {
        List<SegmentDirectory.SegmentName> all = dir.list();
        if (all.isEmpty()) {
            return false;
        }
        SegmentDirectory.SegmentName newest = all.get(all.size() - 1);
        if (newest.day().isAfter(day.day())) {
            return true;
        }
        LOG.log(System.Logger.Level.INFO,
                "day {0} still owns the newest segment {1}; probing the writer heartbeat for {2}",
                day.day(), newest.path().getFileName(), livenessProbe);
        return writerIsGone(newest.path(), livenessProbe);
    }

    /** True if the heartbeat did not advance across the probe window. */
    private static boolean writerIsGone(Path segment, Duration probe) {
        try (SnapshotReader reader = SnapshotReader.open(segment)) {
            long before = reader.heartbeat();
            Thread.sleep(probe.toMillis());
            return reader.heartbeat() == before;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false; // interrupted: claim nothing, let the next run decide
        } catch (UncheckedIOException e) {
            // The segment vanished under us (a raced unlink). Nothing to archive, nothing to claim.
            return false;
        }
    }

    /**
     * Verifies invariant I2: every row in a day's segments carries a timestamp inside that UTC day.
     *
     * <p>Checked from the batch zone maps, so it costs a header read per segment rather than a scan.
     * A violation aborts the day rather than splitting or truncating it: if file-day and event-time
     * disagree, the day partition and the cutover watermark would disagree too, and the union of
     * realtime and historical data would silently gain or lose rows (§3.1, hole 2).
     *
     * <p>Zero-row batches are skipped — they carry no timestamps, and an empty trailing batch is
     * deliberately left unsealed by the writer.
     *
     * @throws DayAlignmentException if any batch's range escapes the day, or is unsealed
     */
    public static void verifyDayAlignment(DaySegments day) {
        long dayStart = day.day().atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
        long nextDay = day.day().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond()
                * 1_000_000_000L;

        for (Path segment : day.segments()) {
            try (SnapshotReader reader = SnapshotReader.open(segment)) {
                List<BatchView> batches = reader.snapshot().batches();
                for (int i = 0; i < batches.size(); i++) {
                    BatchView batch = batches.get(i);
                    if (batch.rowCount() == 0) {
                        continue;
                    }
                    if (!batch.sealed()) {
                        throw new DayAlignmentException(segment, i,
                                "batch is still in progress, so its time range is unpublished — the "
                                        + "segment is not finished (a writer may still be appending)");
                    }
                    if (batch.tsMin() < dayStart || batch.tsMax() >= nextDay) {
                        throw new DayAlignmentException(segment, i,
                                "time range [" + batch.tsMin() + ", " + batch.tsMax() + "] escapes day "
                                        + day.day() + " [" + dayStart + ", " + nextDay + ")");
                    }
                }
            }
        }
    }

    /** A day's segments contain rows that do not belong to that day, so it cannot be rolled as one. */
    public static final class DayAlignmentException extends RuntimeException {
        public DayAlignmentException(Path segment, int batch, String detail) {
            super("day-alignment violation in " + segment + " batch " + batch + ": " + detail);
        }
    }
}
