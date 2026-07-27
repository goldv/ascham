package io.ito.arena.write;

import io.ito.arena.segment.SegmentFormatException;
import io.ito.arena.util.Sha256;
import org.agrona.DirectBuffer;

/**
 * Base class for generated typed appenders (see {@code io.ito.arena.codegen.TypedAppenderGenerator}).
 * A generated subclass adds named, primitive-typed setters (e.g. {@code setBidPx(long)}) that call
 * the {@code protected} forwarders here with a baked-in column ordinal; the forwarders delegate to
 * the shared package-private {@link BatchCursor}, so a typed appender and the {@link GenericAppender}
 * traverse the identical write path and produce byte-identical segments (verified by the equivalence
 * test).
 *
 * <p>{@link #beginRow()} and {@link #endRow()} are {@code final}: generated code physically cannot
 * reorder the publication protocol (spec invariant 2). The constructor verifies the schema the
 * appender was generated against matches the live segment's header hash — a mismatch is a hard
 * failure (invariant 7 on the codegen path), preventing a stale generated appender from silently
 * writing garbage.
 */
public abstract class RowAppender {

    private final BatchCursor cursor;

    protected RowAppender(SegmentWriter writer, String expectedSchemaSha256Hex) {
        this.cursor = writer.cursor();
        String actual = Sha256.toHex(writer.header().schemaSha256());
        if (!actual.equals(expectedSchemaSha256Hex)) {
            throw new SegmentFormatException("typed appender was generated for schema "
                    + expectedSchemaSha256Hex + " but the segment's schema hash is " + actual);
        }
    }

    public final void beginRow() {
        cursor.beginRow();
    }

    public final void endRow() {
        cursor.endRow();
    }

    protected final void setNull(int col) {
        cursor.setNull(col);
    }

    protected final void setBool(int col, boolean value) {
        cursor.setBool(col, value);
    }

    protected final void setByte(int col, byte value) {
        cursor.setIntegral(col, value);
    }

    protected final void setShort(int col, short value) {
        cursor.setIntegral(col, value);
    }

    protected final void setInt(int col, int value) {
        cursor.setIntegral(col, value);
    }

    protected final void setLong(int col, long value) {
        cursor.setIntegral(col, value);
    }

    protected final void setFloat(int col, float value) {
        cursor.setFloat(col, value);
    }

    protected final void setDouble(int col, double value) {
        cursor.setDouble(col, value);
    }

    protected final void setDecimal128(int col, long low, long high) {
        cursor.setDecimal128(col, low, high);
    }

    protected final void setFixedBytes(int col, DirectBuffer src, int offset, int length) {
        cursor.setFixedBytes(col, src, offset, length);
    }

    protected final void setBytes(int col, DirectBuffer src, int offset, int length) {
        cursor.setBytes(col, src, offset, length);
    }
}
