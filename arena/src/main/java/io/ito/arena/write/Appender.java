package io.ito.arena.write;

import org.agrona.DirectBuffer;

/**
 * Row-writing contract: {@code beginRow()}, a setter (or {@code setNull}) per column, then
 * {@code endRow()}. Single-threaded, like everything on the write path.
 *
 * <p>Obtain one from {@link SegmentWriter#appender()} (bound to a single segment; segment
 * exhaustion is fatal) or from {@link io.ito.arena.rotate.RotatingWriter#appender()} (spans
 * rotations; never runs out of room).
 */
public interface Appender {

    void beginRow();

    void endRow();

    /** Explicit null: leaves validity unset. */
    void setNull(int col);

    void setBool(int col, boolean value);

    void setByte(int col, byte value);

    void setShort(int col, short value);

    void setInt(int col, int value);

    void setLong(int col, long value);

    void setFloat(int col, float value);

    void setDouble(int col, double value);

    /** Sets a Decimal128 from its little-endian two's-complement 128-bit value (low, high halves). */
    void setDecimal128(int col, long low, long high);

    void setFixedBytes(int col, DirectBuffer src, int offset, int length);

    void setBytes(int col, DirectBuffer src, int offset, int length);
}
