package io.ascham.write;

import org.agrona.DirectBuffer;

/**
 * Appender that spans segment rotations. Rotation is decided exception-free at exactly two points:
 * {@link #beginRow()} (time policy, or no room for another row) and {@link #setBytes} (varlen
 * exhaustion in the segment's last batch, where the open row is adopted into the successor
 * segment). Producers just write rows — they never see {@code SegmentFullException} and never
 * replay a row. No other operation can hit a capacity wall: fixed-width cells fit by construction
 * once the row is open, and varlen exhaustion away from the last batch migrates within the segment
 * inside {@link BatchCursor}.
 *
 * <p>No cursor is cached: rotations can also be initiated outside this appender (forced rotation,
 * heartbeat), so every operation resolves the live segment through the roller.
 *
 * <p>Obtain from {@link io.ascham.rotate.RotatingWriter#appender()}.
 */
public final class RollingAppender implements Appender {

    private final SegmentRoller roller;

    public RollingAppender(SegmentRoller roller) {
        this.roller = roller;
    }

    /** True between {@code beginRow()} and {@code endRow()}; rotation guards key off this. */
    public boolean rowOpen() {
        return cursor().rowOpen();
    }

    @Override
    public void beginRow() {
        SegmentWriter live = roller.current();
        if (roller.rotationDue() || live.cursor().rowCountRotationDue()) {
            live = roller.rotate();
        }
        live.cursor().beginRow();
    }

    @Override
    public void endRow() {
        cursor().endRow();
    }

    @Override
    public void setNull(int col) {
        cursor().setNull(col);
    }

    @Override
    public void setBool(int col, boolean value) {
        cursor().setBool(col, value);
    }

    @Override
    public void setByte(int col, byte value) {
        cursor().setIntegral(col, value);
    }

    @Override
    public void setShort(int col, short value) {
        cursor().setIntegral(col, value);
    }

    @Override
    public void setInt(int col, int value) {
        cursor().setIntegral(col, value);
    }

    @Override
    public void setLong(int col, long value) {
        cursor().setIntegral(col, value);
    }

    @Override
    public void setFloat(int col, float value) {
        cursor().setFloat(col, value);
    }

    @Override
    public void setDouble(int col, double value) {
        cursor().setDouble(col, value);
    }

    @Override
    public void setDecimal128(int col, long low, long high) {
        cursor().setDecimal128(col, low, high);
    }

    @Override
    public void setFixedBytes(int col, DirectBuffer src, int offset, int length) {
        cursor().setFixedBytes(col, src, offset, length);
    }

    @Override
    public void setBytes(int col, DirectBuffer src, int offset, int length) {
        SegmentWriter live = roller.current();
        if (live.cursor().varlenRotationDue(col, length)) {
            SegmentWriter next = roller.openSuccessor();
            // Adopt before retiring: adoption reads the old segment's mapping (use-after-unmap otherwise).
            next.cursor().adoptOpenRowFrom(live.cursor());
            roller.retire(live);
            live = next;
        }
        live.cursor().setBytes(col, src, offset, length);
    }

    private BatchCursor cursor() {
        return roller.current().cursor();
    }
}
