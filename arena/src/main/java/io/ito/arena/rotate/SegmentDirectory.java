package io.ito.arena.rotate;

import io.ito.arena.segment.SegmentFile;
import io.ito.arena.segment.SegmentHeader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The on-disk directory of segments for one table (in production {@code baseDir} is
 * {@code /dev/shm/ito}). Handles naming, listing, next-sequence allocation, and eviction. Eviction
 * is {@link Files#delete} on the shm path — i.e. {@code shm_unlink}: the kernel refcount keeps the
 * inode alive for readers that still have it mapped, which is exactly the reclamation semantics we
 * want (spec M5).
 *
 * <p>A segment name declares the roll interval its rows belong to:
 * <ul>
 *   <li>{@code <yyyyMMdd>.<seq>.arena} — a daily cycle ({@link RollCycle#DAILY}); the interval is
 *       the whole UTC day. This is the original format, unchanged, so daily arenas need no
 *       migration.</li>
 *   <li>{@code <yyyyMMdd>.<HHmm>.<minutes>m.<seq>.arena} — a sub-day cycle; the interval starts at
 *       {@code HHmm} UTC and lasts {@code minutes}. The duration is in the name because grouping
 *       and completeness must be decidable from names alone — a start time by itself cannot say
 *       when a dead writer's last interval ends.</li>
 * </ul>
 */
public final class SegmentDirectory {

    private static final Pattern NAME = Pattern.compile("(\\d{8})\\.(?:(\\d{4})\\.(\\d+)m\\.)?(\\d+)\\.arena");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");

    private final Path tableDir;

    public SegmentDirectory(Path baseDir, String table) {
        this.tableDir = baseDir.resolve(table);
        try {
            Files.createDirectories(tableDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create segment directory " + tableDir, e);
        }
    }

    public Path tableDir() {
        return tableDir;
    }

    public Path segmentPath(Instant intervalStart, RollCycle cycle, int seq) {
        LocalDate day = LocalDate.ofInstant(intervalStart, ZoneOffset.UTC);
        String prefix = cycle.daily()
                ? day.format(DAY)
                : day.format(DAY) + "." + LocalTime.ofInstant(intervalStart, ZoneOffset.UTC).format(TIME)
                        + "." + cycle.minutes() + "m";
        return tableDir.resolve(prefix + "." + seq + ".arena");
    }

    /** All segments in this table directory, sorted oldest-first by (interval start, cycle, seq). */
    public List<SegmentName> list() {
        List<SegmentName> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(tableDir)) {
            entries.forEach(p -> {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (m.matches()) {
                    names.add(parse(m, p));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + tableDir, e);
        }
        names.sort(null);
        return names;
    }

    private static SegmentName parse(Matcher m, Path path) {
        LocalDate day = LocalDate.parse(m.group(1), DAY);
        int seq = Integer.parseInt(m.group(4));
        if (m.group(2) == null) {
            return new SegmentName(day.atStartOfDay(ZoneOffset.UTC).toInstant(), RollCycle.DAILY, seq, path);
        }
        Instant start = day.atTime(LocalTime.parse(m.group(2), TIME)).toInstant(ZoneOffset.UTC);
        RollCycle cycle = new RollCycle(Integer.parseInt(m.group(3)));
        return new SegmentName(start, cycle, seq, path);
    }

    /** Next unused sequence number within the {@code (intervalStart, cycle)} interval. */
    public int nextSeq(Instant intervalStart, RollCycle cycle) {
        int next = 0;
        for (SegmentName s : list()) {
            if (s.start().equals(intervalStart) && s.cycle().equals(cycle)) {
                next = Math.max(next, s.seq() + 1);
            }
        }
        return next;
    }

    /** Unlinks a segment file. Readers that still have it mapped keep reading (kernel refcount). */
    public void unlink(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot unlink " + path, e);
        }
    }

    /** The writer epoch recorded in a segment's header (opens and closes the file). */
    public long readEpoch(Path path) {
        try (SegmentFile file = SegmentFile.openReadOnly(path)) {
            SegmentHeader header = new SegmentHeader(file.control());
            header.verifyMagicAndVersion();
            return header.writerEpoch();
        }
    }

    /** Highest writer epoch across all segments, for computing the next epoch on writer restart. */
    public OptionalLong latestEpoch() {
        Optional<SegmentName> newest = list().stream().max(SegmentName::compareTo);
        return newest.map(s -> OptionalLong.of(readEpoch(s.path()))).orElseGet(OptionalLong::empty);
    }

    /** A parsed segment file name, ordered oldest-first by (interval start, cycle, seq). */
    public record SegmentName(Instant start, RollCycle cycle, int seq, Path path)
            implements Comparable<SegmentName> {

        /** The UTC day the interval lies in (a cycle divides 24h, so it never spans two days). */
        public LocalDate day() {
            return LocalDate.ofInstant(start, ZoneOffset.UTC);
        }

        /** The exclusive end of the interval this segment's rows belong to. */
        public Instant end() {
            return cycle.end(start);
        }

        @Override
        public int compareTo(SegmentName o) {
            int c = start.compareTo(o.start);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(cycle.minutes(), o.cycle.minutes());
            return c != 0 ? c : Integer.compare(seq, o.seq);
        }
    }
}
