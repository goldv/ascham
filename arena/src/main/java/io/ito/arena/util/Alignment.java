package io.ito.arena.util;

import org.agrona.BitUtil;

/**
 * Arena alignment vocabulary. The rounding arithmetic is delegated to Agrona's
 * {@link BitUtil#align(long, long)} (which has the {@code long} overload we need, since data-region
 * offsets exceed 2 GB); this class only names the two arena-specific alignments and the bitmap-size
 * helper that {@code BitUtil} does not provide.
 *
 * <ul>
 *   <li>{@link #BUFFER_ALIGN}: every buffer base is 64-byte aligned (spec invariant 5 — Arrow and
 *       DuckDB readers are entitled to assume this).</li>
 *   <li>{@link #PAGE_ALIGN}: every batch region is padded to a 4 KiB page so a batch never ends
 *       flush against an unmapped page (spec invariant 6 — readers overread in 64-byte chunks past
 *       the row count).</li>
 * </ul>
 */
public final class Alignment {

    /** Buffer base alignment, in bytes. Numerically a cache line, but a distinct Arrow requirement. */
    public static final int BUFFER_ALIGN = 64;

    /** Batch region alignment, in bytes (one page). */
    public static final int PAGE_ALIGN = 4096;

    private Alignment() {
    }

    /** Rounds {@code x} up to the next multiple of 64. */
    public static long align64(long x) {
        return BitUtil.align(x, BUFFER_ALIGN);
    }

    /** Rounds {@code x} up to the next multiple of 4096. */
    public static long alignPage(long x) {
        return BitUtil.align(x, PAGE_ALIGN);
    }

    /** Number of bytes needed for a validity/bool bitmap covering {@code rows} rows. */
    public static long bitmapBytes(int rows) {
        return (rows + 7L) / 8L;
    }
}
