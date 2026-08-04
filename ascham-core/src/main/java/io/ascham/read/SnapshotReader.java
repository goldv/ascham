package io.ascham.read;

import io.ascham.layout.LayoutCodec;
import io.ascham.layout.LayoutDescriptor;
import io.ascham.schema.ArenaSchema;
import io.ascham.segment.Regions;
import io.ascham.segment.SegmentFile;
import io.ascham.segment.SegmentFormatException;
import io.ascham.segment.SegmentHeader;
import io.ascham.util.Sha256;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Opens a segment read-only and hands out consistent {@link Snapshot}s. The segment is
 * self-describing: the embedded schema and layout descriptor are read from the file, so the reader
 * needs no build-time coupling to the writer.
 *
 * <p>At open, the magic/version and the schema hash are verified — a schema hash mismatch is a hard
 * failure (spec invariant 7: a reader misinterpreting a layout produces plausible garbage, the worst
 * failure mode). Reader pointers stay valid for the life of the segment because the writer never
 * rewinds (invariant 1), so no epoch reclamation or reader registration is needed.
 */
public final class SnapshotReader implements AutoCloseable {

    private final SegmentFile file;
    private final SegmentHeader header;
    private final Regions regions;
    private final ArenaSchema schema;
    private final LayoutDescriptor layout;
    private final BufferAllocator allocator;

    private SnapshotReader(SegmentFile file, SegmentHeader header, Regions regions,
                           ArenaSchema schema, LayoutDescriptor layout) {
        this.file = file;
        this.header = header;
        this.regions = regions;
        this.schema = schema;
        this.layout = layout;
        this.allocator = new RootAllocator();
    }

    public static SnapshotReader open(Path path) {
        SegmentFile file = SegmentFile.openReadOnly(path);
        try {
            SegmentHeader header = new SegmentHeader(file.control());
            header.verifyMagicAndVersion();
            Regions regions = header.regions();

            byte[] schemaBytes = new byte[Math.toIntExact(regions.schemaLength())];
            file.control().getBytes(Math.toIntExact(regions.schemaOffset()), schemaBytes);
            if (!Arrays.equals(Sha256.hash(schemaBytes), header.schemaSha256())) {
                throw new SegmentFormatException(
                        "schema hash mismatch: the embedded schema does not match the header hash");
            }
            Schema arrowSchema = Schema.deserializeMessage(ByteBuffer.wrap(schemaBytes));
            ArenaSchema schema = ArenaSchema.load(arrowSchema);
            LayoutDescriptor layout = LayoutCodec.decode(
                    file.data(), Math.toIntExact(regions.layoutOffset()), Math.toIntExact(regions.layoutLength()));

            return new SnapshotReader(file, header, regions, schema, layout);
        } catch (RuntimeException e) {
            file.close();
            throw e;
        }
    }

    /** Freezes a consistent view of the catalog. A stale snapshot is always safe; never re-read. */
    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public long writerEpoch() {
        return header.writerEpoch();
    }

    public long heartbeat() {
        return header.heartbeatAcquire();
    }

    public ArenaSchema schema() {
        return schema;
    }

    @Override
    public void close() {
        allocator.close();
        file.close();
    }

    // --- Package-internal accessors used by Snapshot / BatchView. ---

    SegmentFile file() {
        return file;
    }

    SegmentHeader header() {
        return header;
    }

    Regions regions() {
        return regions;
    }

    LayoutDescriptor layout() {
        return layout;
    }

    ArenaSchema arenaSchema() {
        return schema;
    }

    BufferAllocator allocator() {
        return allocator;
    }
}
