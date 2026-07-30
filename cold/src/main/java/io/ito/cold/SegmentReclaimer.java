package io.ito.cold;

import io.ito.arena.rotate.SegmentDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Releases arena segments whose rows are safely in the historical store — the only component in the
 * system that deletes arena data (invariant I3, docs/cold-tier-design-plan.md §3.2).
 *
 * <p>Three rules keep that safe, and each guards a different failure:
 *
 * <ul>
 *   <li><b>Only what the roll log names.</b> A segment is reclaimed because a specific committed
 *       archive row says it was copied — never because it merely looks old. The log's segment list
 *       is the audit trail and the reclaim set at once, which is why the roll names its inputs
 *       explicitly rather than re-listing the directory.</li>
 *   <li><b>Only after a grace period.</b> Readers cache the realtime/historical cutover, so a
 *       just-rolled day may still be served from the arena by a query that has not refreshed yet.
 *       The grace window must comfortably exceed that cache TTL (60 s default vs 15 min here).</li>
 *   <li><b>Never the newest segment.</b> A defence against a corrupted or hand-edited log: the
 *       newest segment is the one a live writer appends to, and unlinking it would be unrecoverable
 *       even though already-mapped readers would survive.</li>
 * </ul>
 *
 * <p>Unlinking is not destructive to in-flight readers: the kernel keeps an unlinked inode alive
 * until the last mapping is dropped (arena M5 semantics). It only stops <em>new</em> readers.
 */
public final class SegmentReclaimer {

    private static final System.Logger LOG = System.getLogger(SegmentReclaimer.class.getName());

    /** What one reclaim pass released. */
    public record Result(String table, List<Path> unlinked, long bytesFreed) {
        public boolean isEmpty() {
            return unlinked.isEmpty();
        }
    }

    private final ColdConfig config;
    private final RollExecutor executor;
    private final Duration grace;

    public SegmentReclaimer(ColdConfig config, RollExecutor executor, Duration grace) {
        this.config = config;
        this.executor = executor;
        this.grace = grace;
    }

    /** Releases every segment of {@code table} that is archived, past grace, and still present. */
    public Result reclaim(String table) {
        Path tableDir = config.arenaBaseDir().resolve(table);
        if (!Files.isDirectory(tableDir)) {
            return new Result(table, List.of(), 0);
        }
        SegmentDirectory dir = new SegmentDirectory(config.arenaBaseDir(), table);
        List<SegmentDirectory.SegmentName> present = dir.list();
        if (present.isEmpty()) {
            return new Result(table, List.of(), 0);
        }
        Path newest = present.get(present.size() - 1).path();

        List<Path> unlinked = new ArrayList<>();
        long bytes = 0;
        for (RollExecutor.ReclaimableDay day : executor.reclaimable(table, grace)) {
            for (String name : day.segmentNames()) {
                Path segment = resolveWithin(tableDir, name);
                if (segment == null || !Files.exists(segment)) {
                    continue; // already reclaimed by an earlier run, or never existed
                }
                if (segment.equals(newest)) {
                    LOG.log(System.Logger.Level.WARNING,
                            "refusing to reclaim {0}: it is the newest segment in {1}, which a live "
                                    + "writer may still be appending to", name, tableDir);
                    continue;
                }
                long size = sizeOf(segment);
                dir.unlink(segment);
                unlinked.add(segment);
                bytes += size;
                LOG.log(System.Logger.Level.INFO, "reclaimed {0} ({1} bytes, archived day {2})",
                        name, size, day.day());
            }
        }
        return new Result(table, List.copyOf(unlinked), bytes);
    }

    /**
     * Resolves a logged segment name inside the table directory, rejecting anything that escapes it.
     * The name comes from a stored string, so it is treated as untrusted input rather than a path.
     */
    private static Path resolveWithin(Path tableDir, String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Path resolved = tableDir.resolve(trimmed).normalize();
        if (!resolved.startsWith(tableDir.normalize()) || resolved.equals(tableDir.normalize())) {
            LOG.log(System.Logger.Level.ERROR,
                    "roll log names a segment outside its table directory, refusing to touch it: {0}", name);
            return null;
        }
        return resolved;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (java.io.IOException e) {
            return 0;
        }
    }
}
