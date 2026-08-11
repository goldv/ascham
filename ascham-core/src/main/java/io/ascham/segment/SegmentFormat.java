// GENERATED from spec/format-manifest.toml (sha256 c85825dcb549) by spec/generate_format.py — DO NOT EDIT.
// Regenerate with: python3 spec/generate_format.py --lang java --repo .
package io.ascham.segment;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Every segment-format constant in one place, generated from the machine-readable contract
 * in {@code spec/format-manifest.toml}; each field below cites its offset in
 * {@code docs/segment-format.md}. Nothing in this file may change without a
 * {@link #FORMAT_VERSION} bump once any segment has been written — it is the cross-language
 * byte contract.
 *
 * <p>All multi-byte values are little-endian. All offsets written into the header region table and
 * every catalog {@code base_offset} are segment-relative (spec invariant 4: never a pointer).
 */
public final class SegmentFormat {

    /** 8-byte segment magic. Confirmed at the ascham rename; changing it again is a format break. */
    public static final byte[] MAGIC = "ASCHAMFM".getBytes(StandardCharsets.US_ASCII);

    public static final int MAGIC_LENGTH = 8;
    public static final int FORMAT_VERSION = 1;
    public static final int HEADER_LENGTH = 4096;

    // --- Header field offsets (see docs/segment-format.md "Header"). ---
    public static final int HDR_MAGIC = 0;
    public static final int HDR_FORMAT_VERSION = 8;
    public static final int HDR_HEADER_LENGTH = 12;
    public static final int HDR_SCHEMA_SHA256 = 16;   // 32 bytes
    public static final int HDR_SEGMENT_SEQUENCE = 48;
    public static final int HDR_ARENA_CAPACITY = 56;
    public static final int HDR_WRITER_EPOCH = 64;
    public static final int HDR_BATCH_ROWS = 72;
    public static final int HDR_BATCH_STRIDE = 80;
    /** Liveness counter, alone on its cache line (release/acquire). */
    public static final int HDR_HEARTBEAT = 128;
    /** Number of catalog entries opened, alone on its cache line (release/acquire). */
    public static final int HDR_ACTIVE_BATCH_COUNT = 192;

    // Region table (offset,length) pairs, starting at 256.
    public static final int HDR_REGION_TABLE = 256;
    public static final int REGION_SCHEMA = 0;
    public static final int REGION_LAYOUT = 1;
    public static final int REGION_CATALOG = 2;
    public static final int REGION_DATA = 3;
    /** Reserved in v1 (offset/length both 0); populated by a future format version (invariant 8). */
    public static final int REGION_FAMILY_WATERMARKS = 4;
    public static final int REGION_COUNT = 5;
    public static final int REGION_ENTRY_SIZE = 16; // int64 offset + int64 length

    // --- Catalog entry offsets (see docs/segment-format.md "Batch catalog"). ---
    public static final int CATALOG_ENTRY_SIZE = 64; // one cache line
    public static final int ENT_LENGTH = 0;
    public static final int ENT_BASE_OFFSET = 8;
    public static final int ENT_TS_MIN = 16;
    public static final int ENT_TS_MAX = 24;
    public static final int ENT_STAT_MIN = 32;
    public static final int ENT_STAT_MAX = 40;
    public static final int ENT_SEAL_NANOS = 48;
    // bytes 56..64 reserved

    /**
     * Catalog {@code length} bit 63: set means the batch is still accumulating. Row count is
     * {@code length & ROW_COUNT_MASK}. A negative sentinel is not used: {@code -0 == 0} would make a
     * zero-row in-progress batch indistinguishable from a sealed empty one (spec).
     */
    public static final long IN_PROGRESS_BIT = 1L << 63;
    public static final long ROW_COUNT_MASK = Long.MAX_VALUE;

    /** Layout descriptor codec version (docs/segment-format.md "Layout descriptor region"). */
    public static final int LAYOUT_CODEC_VERSION = 1;

    /** Buffer-base alignment (spec invariant 5). Mirrored by {@code util.Alignment}; asserted equal there. */
    public static final int BUFFER_ALIGN = 64;

    /** Batch-stride alignment (spec invariant 6). Mirrored by {@code util.Alignment}; asserted equal there. */
    public static final int PAGE_ALIGN = 4096;

    /**
     * Segment filename grammar: {@code <yyyyMMdd>.<seq>.ascham} (daily cycle) or
     * {@code <yyyyMMdd>.<HHmm>.<minutes>m.<seq>.ascham} (sub-day). Groups: date, start HHmm,
     * cycle minutes, sequence. The {1,9} bounds keep every numeric group inside int32 before any
     * parse; in-flight {@code *.tmp.*} files are excluded by non-match.
     */
    public static final Pattern SEGMENT_FILENAME_PATTERN = Pattern.compile("^(\\d{8})\\.(?:(\\d{4})\\.(\\d{1,9})m\\.)?(\\d{1,9})\\.ascham$");

    private SegmentFormat() {
    }

    /** Segment-relative byte offset of the {@code offset} slot of region {@code regionIndex}. */
    public static int regionOffsetField(int regionIndex) {
        return HDR_REGION_TABLE + regionIndex * REGION_ENTRY_SIZE;
    }

    /** Segment-relative byte offset of the {@code length} slot of region {@code regionIndex}. */
    public static int regionLengthField(int regionIndex) {
        return regionOffsetField(regionIndex) + Long.BYTES;
    }

    /** Row count encoded in a catalog {@code length} value (masks off the in-progress bit). */
    public static long rowCount(long length) {
        return length & ROW_COUNT_MASK;
    }

    /** Whether a catalog {@code length} value denotes an in-progress (unsealed) batch. */
    public static boolean isInProgress(long length) {
        return (length & IN_PROGRESS_BIT) != 0;
    }
}
