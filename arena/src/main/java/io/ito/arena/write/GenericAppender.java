package io.ito.arena.write;

import org.agrona.DirectBuffer;

/**
 * Descriptor-driven appender for non-JVM-idiomatic producers, CSV/replay loaders, and tests.
 * Correctness over speed: every call dispatches on column ordinal and the {@link BatchCursor}
 * validates the column kind. All publication is delegated to the cursor, so this appender and the
 * generated typed appender produce byte-identical output (verified by the equivalence test).
 *
 * <p>Usage: {@code beginRow()}, a setter (or {@code setNull}) per column, then {@code endRow()}.
 */
public final class GenericAppender {

    private final BatchCursor cursor;

    GenericAppender(BatchCursor cursor) {
        this.cursor = cursor;
    }

    public void beginRow() {
        cursor.beginRow();
    }

    public void endRow() {
        cursor.endRow();
    }

    public void setNull(int col) {
        cursor.setNull(col);
    }

    public void setBool(int col, boolean value) {
        cursor.setBool(col, value);
    }

    public void setByte(int col, byte value) {
        cursor.setIntegral(col, value);
    }

    public void setShort(int col, short value) {
        cursor.setIntegral(col, value);
    }

    public void setInt(int col, int value) {
        cursor.setIntegral(col, value);
    }

    public void setLong(int col, long value) {
        cursor.setIntegral(col, value);
    }

    public void setFloat(int col, float value) {
        cursor.setFloat(col, value);
    }

    public void setDouble(int col, double value) {
        cursor.setDouble(col, value);
    }

    /** Sets a Decimal128 from its little-endian two's-complement 128-bit value (low, high halves). */
    public void setDecimal128(int col, long low, long high) {
        cursor.setDecimal128(col, low, high);
    }

    public void setFixedBytes(int col, DirectBuffer src, int offset, int length) {
        cursor.setFixedBytes(col, src, offset, length);
    }

    public void setBytes(int col, DirectBuffer src, int offset, int length) {
        cursor.setBytes(col, src, offset, length);
    }
}
