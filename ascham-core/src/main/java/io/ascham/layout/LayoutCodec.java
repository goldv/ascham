package io.ascham.layout;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Fixed little-endian encoding of a {@link LayoutDescriptor} for the segment's descriptor region.
 * The descriptor is derived from the schema, but written out so readers need no build-time coupling
 * to the writer. Encoding is deterministic and the round-trip {@code decode(encode(d)) == d} is an
 * identity (a property test pins both).
 */
public final class LayoutCodec {

    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    private static final int CODEC_VERSION = 1;

    private LayoutCodec() {
    }

    /** Number of bytes {@link #encode} will write for {@code descriptor}. */
    public static int encodedSize(LayoutDescriptor descriptor) {
        int size = Integer.BYTES        // codec version
                + Integer.BYTES         // batchRows
                + Long.BYTES            // batchStrideBytes
                + Integer.BYTES;        // familyCount
        for (String family : descriptor.families()) {
            size += Integer.BYTES + utf8Length(family);
        }
        size += Integer.BYTES;          // columnCount
        for (ColumnLayout column : descriptor.columns()) {
            size += Integer.BYTES + utf8Length(column.name()); // name
            size += Integer.BYTES * 4;   // ordinal, kind, familyId, elementWidth
            size += Long.BYTES * 5;      // validity, data, dataCap, offsets, varlenCap
        }
        return size;
    }

    /** Encodes {@code descriptor} into {@code buffer} at {@code offset}; returns bytes written. */
    public static int encode(LayoutDescriptor descriptor, MutableDirectBuffer buffer, int offset) {
        Cursor c = new Cursor(offset);
        putInt(buffer, c, CODEC_VERSION);
        putInt(buffer, c, descriptor.batchRows());
        putLong(buffer, c, descriptor.batchStrideBytes());

        putInt(buffer, c, descriptor.families().size());
        for (String family : descriptor.families()) {
            putString(buffer, c, family);
        }

        putInt(buffer, c, descriptor.columnCount());
        for (ColumnLayout column : descriptor.columns()) {
            putString(buffer, c, column.name());
            putInt(buffer, c, column.ordinal());
            putInt(buffer, c, column.kind().ordinal());
            putInt(buffer, c, column.familyId());
            putInt(buffer, c, column.elementWidth());
            putLong(buffer, c, column.validityOffset());
            putLong(buffer, c, column.dataOffset());
            putLong(buffer, c, column.dataCapacityBytes());
            putLong(buffer, c, column.offsetsOffset());
            putLong(buffer, c, column.varlenCapacityBytes());
        }
        return c.pos - offset;
    }

    /** Decodes a descriptor from {@code buffer} starting at {@code offset}. */
    public static LayoutDescriptor decode(DirectBuffer buffer, int offset, int length) {
        Cursor c = new Cursor(offset);
        int version = getInt(buffer, c);
        if (version != CODEC_VERSION) {
            throw new IllegalArgumentException("unsupported layout codec version: " + version);
        }
        int batchRows = getInt(buffer, c);
        long batchStride = getLong(buffer, c);

        int familyCount = getInt(buffer, c);
        List<String> families = new ArrayList<>(familyCount);
        for (int i = 0; i < familyCount; i++) {
            families.add(getString(buffer, c));
        }

        int columnCount = getInt(buffer, c);
        List<ColumnLayout> columns = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            String name = getString(buffer, c);
            int ordinal = getInt(buffer, c);
            PhysicalKind kind = PhysicalKind.values()[getInt(buffer, c)];
            int familyId = getInt(buffer, c);
            int elementWidth = getInt(buffer, c);
            long validity = getLong(buffer, c);
            long data = getLong(buffer, c);
            long dataCap = getLong(buffer, c);
            long offsets = getLong(buffer, c);
            long varlenCap = getLong(buffer, c);
            columns.add(new ColumnLayout(name, ordinal, kind, familyId, elementWidth,
                    validity, data, dataCap, offsets, varlenCap));
        }

        int consumed = c.pos - offset;
        if (consumed != length) {
            throw new IllegalArgumentException(
                    "layout descriptor length mismatch: declared " + length + ", consumed " + consumed);
        }
        return new LayoutDescriptor(columns, batchRows, batchStride, families);
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void putString(MutableDirectBuffer buffer, Cursor c, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        putInt(buffer, c, bytes.length);
        buffer.putBytes(c.pos, bytes);
        c.pos += bytes.length;
    }

    private static String getString(DirectBuffer buffer, Cursor c) {
        int len = getInt(buffer, c);
        byte[] bytes = new byte[len];
        buffer.getBytes(c.pos, bytes);
        c.pos += len;
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void putInt(MutableDirectBuffer buffer, Cursor c, int v) {
        buffer.putInt(c.pos, v, ORDER);
        c.pos += Integer.BYTES;
    }

    private static void putLong(MutableDirectBuffer buffer, Cursor c, long v) {
        buffer.putLong(c.pos, v, ORDER);
        c.pos += Long.BYTES;
    }

    private static int getInt(DirectBuffer buffer, Cursor c) {
        int v = buffer.getInt(c.pos, ORDER);
        c.pos += Integer.BYTES;
        return v;
    }

    private static long getLong(DirectBuffer buffer, Cursor c) {
        long v = buffer.getLong(c.pos, ORDER);
        c.pos += Long.BYTES;
        return v;
    }

    /** Mutable position within the target buffer. */
    private static final class Cursor {
        int pos;

        Cursor(int start) {
            this.pos = start;
        }
    }
}
