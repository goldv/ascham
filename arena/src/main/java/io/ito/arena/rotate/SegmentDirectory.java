package io.ito.arena.rotate;

import io.ito.arena.segment.SegmentFile;
import io.ito.arena.segment.SegmentHeader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The on-disk directory of segments for one table: {@code <baseDir>/<table>/<yyyyMMdd>.<seq>.arena}
 * (in production {@code baseDir} is {@code /dev/shm/ito}). Handles naming, listing, next-sequence
 * allocation, and eviction. Eviction is {@link Files#delete} on the shm path — i.e. {@code shm_unlink}:
 * the kernel refcount keeps the inode alive for readers that still have it mapped, which is exactly
 * the reclamation semantics we want (spec M5).
 */
public final class SegmentDirectory {

    private static final Pattern NAME = Pattern.compile("(\\d{8})\\.(\\d+)\\.arena");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

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

    public Path segmentPath(LocalDate day, int seq) {
        return tableDir.resolve(day.format(DAY) + "." + seq + ".arena");
    }

    /** All segments in this table directory, sorted oldest-first by (day, seq). */
    public List<SegmentName> list() {
        List<SegmentName> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(tableDir)) {
            entries.forEach(p -> {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (m.matches()) {
                    names.add(new SegmentName(LocalDate.parse(m.group(1), DAY), Integer.parseInt(m.group(2)), p));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + tableDir, e);
        }
        names.sort(null);
        return names;
    }

    /** Next unused sequence number for {@code day} (max existing + 1, or 0). */
    public int nextSeq(LocalDate day) {
        int next = 0;
        for (SegmentName s : list()) {
            if (s.day().equals(day)) {
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

    /** A parsed segment file name, ordered oldest-first by (day, seq). */
    public record SegmentName(LocalDate day, int seq, Path path) implements Comparable<SegmentName> {
        @Override
        public int compareTo(SegmentName o) {
            int c = day.compareTo(o.day);
            return c != 0 ? c : Integer.compare(seq, o.seq);
        }
    }
}
