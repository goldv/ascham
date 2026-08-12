package io.ascham.layout;

import com.google.flatbuffers.FlatBufferBuilder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;

/**
 * Encoding of a {@link LayoutDescriptor} for the segment's descriptor region: a Flatbuffers buffer
 * per {@code format/Layout.fbs} (format version 2+), file identifier {@code ALD2}. The descriptor
 * is derived from the schema, but written out so readers need no build-time coupling to the writer;
 * the .fbs is the cross-language wire authority, and language bindings are generated at development
 * time and checked in (see {@code format/README.md}).
 *
 * <p><b>Canonical encoding</b> (pinned by {@code conformance/layout_vectors.jsonl} and the
 * determinism property test): the builder runs with {@code forceDefaults(true)}, per-table fields
 * are written by the generated {@code create*} helpers (fixed slot order), and vectors are built
 * in ordinal order — so identical descriptors always serialize to identical bytes, and
 * {@code decode(encode(d)) == d} is an identity.
 */
public final class LayoutCodec {

    private static final String FILE_IDENTIFIER = "ALD2";

    private LayoutCodec() {
    }

    /** Encodes {@code descriptor} canonically; the returned array is exactly the region content. */
    public static byte[] encode(LayoutDescriptor descriptor) {
        FlatBufferBuilder builder = new FlatBufferBuilder(1024);
        builder.forceDefaults(true);

        int[] columnOffsets = new int[descriptor.columnCount()];
        for (int i = 0; i < columnOffsets.length; i++) {
            ColumnLayout column = descriptor.columns().get(i);
            int name = builder.createString(column.name());
            columnOffsets[i] = io.ascham.flatbuf.ColumnLayout.createColumnLayout(builder,
                    name, column.ordinal(), (byte) column.kind().wireValue(), column.familyId(),
                    column.elementWidth(), column.validityOffset(), column.dataOffset(),
                    column.dataCapacityBytes(), column.offsetsOffset(), column.varlenCapacityBytes());
        }
        int columns = io.ascham.flatbuf.LayoutDescriptor.createColumnsVector(builder, columnOffsets);

        int[] familyOffsets = new int[descriptor.families().size()];
        for (int i = 0; i < familyOffsets.length; i++) {
            familyOffsets[i] = builder.createString(descriptor.families().get(i));
        }
        int families = io.ascham.flatbuf.LayoutDescriptor.createFamiliesVector(builder, familyOffsets);

        int root = io.ascham.flatbuf.LayoutDescriptor.createLayoutDescriptor(builder,
                descriptor.batchRows(), descriptor.batchStrideBytes(), families, columns);
        builder.finish(root, FILE_IDENTIFIER);
        return builder.sizedByteArray();
    }

    /** Decodes a descriptor from {@code buffer} at {@code [offset, offset + length)}. */
    public static LayoutDescriptor decode(DirectBuffer buffer, int offset, int length) {
        // The descriptor is a few KB and decoded once per segment open, so copying out of the
        // mapping is fine and gives the flatbuffers accessors a plain, bounds-checked ByteBuffer.
        if (length < 12) {
            throw new IllegalArgumentException("layout descriptor region too short: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.getBytes(offset, bytes);
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        if (!io.ascham.flatbuf.LayoutDescriptor.LayoutDescriptorBufferHasIdentifier(bb)) {
            throw new IllegalArgumentException(
                    "layout descriptor region is not an " + FILE_IDENTIFIER + " flatbuffer");
        }
        io.ascham.flatbuf.LayoutDescriptor fb =
                io.ascham.flatbuf.LayoutDescriptor.getRootAsLayoutDescriptor(bb);

        List<String> families = new ArrayList<>(fb.familiesLength());
        for (int i = 0; i < fb.familiesLength(); i++) {
            families.add(fb.families(i));
        }
        List<ColumnLayout> columns = new ArrayList<>(fb.columnsLength());
        io.ascham.flatbuf.ColumnLayout col = new io.ascham.flatbuf.ColumnLayout();
        for (int i = 0; i < fb.columnsLength(); i++) {
            fb.columns(col, i);
            String name = col.name();
            if (name == null) {
                throw new IllegalArgumentException("layout descriptor column " + i + " has no name");
            }
            columns.add(new ColumnLayout(name, col.ordinal(), PhysicalKind.fromWire(col.kind()),
                    col.familyId(), col.elementWidth(), col.validityOffset(), col.dataOffset(),
                    col.dataCapacityBytes(), col.offsetsOffset(), col.varlenCapacityBytes()));
        }
        return new LayoutDescriptor(columns, fb.batchRows(), fb.batchStrideBytes(), families);
    }
}
