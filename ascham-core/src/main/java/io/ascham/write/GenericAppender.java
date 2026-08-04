package io.ascham.write;

import org.agrona.DirectBuffer;

/**
 * Descriptor-driven appender bound to a single segment. Every call dispatches on column ordinal and
 * the {@link BatchCursor} validates the column kind; all publication is delegated to the cursor.
 *
 * <p>Usage: {@code beginRow()}, a setter (or {@code setNull}) per column, then {@code endRow()}.
 */
public final class GenericAppender implements Appender {

    private final BatchCursor cursor;

    GenericAppender(BatchCursor cursor) {
        this.cursor = cursor;
    }

    @Override
    public void beginRow() {
        cursor.beginRow();
    }

    @Override
    public void endRow() {
        cursor.endRow();
    }

    @Override
    public void setNull(int col) {
        cursor.setNull(col);
    }

    @Override
    public void setBool(int col, boolean value) {
        cursor.setBool(col, value);
    }

    @Override
    public void setByte(int col, byte value) {
        cursor.setIntegral(col, value);
    }

    @Override
    public void setShort(int col, short value) {
        cursor.setIntegral(col, value);
    }

    @Override
    public void setInt(int col, int value) {
        cursor.setIntegral(col, value);
    }

    @Override
    public void setLong(int col, long value) {
        cursor.setIntegral(col, value);
    }

    @Override
    public void setFloat(int col, float value) {
        cursor.setFloat(col, value);
    }

    @Override
    public void setDouble(int col, double value) {
        cursor.setDouble(col, value);
    }

    @Override
    public void setDecimal128(int col, long low, long high) {
        cursor.setDecimal128(col, low, high);
    }

    @Override
    public void setFixedBytes(int col, DirectBuffer src, int offset, int length) {
        cursor.setFixedBytes(col, src, offset, length);
    }

    @Override
    public void setBytes(int col, DirectBuffer src, int offset, int length) {
        cursor.setBytes(col, src, offset, length);
    }
}
