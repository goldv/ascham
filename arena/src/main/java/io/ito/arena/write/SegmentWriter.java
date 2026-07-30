package io.ito.arena.write;

import io.ito.arena.layout.LayoutCodec;
import io.ito.arena.layout.LayoutDescriptor;
import io.ito.arena.layout.Layouts;
import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.CanonicalSchema;
import io.ito.arena.segment.CatalogCodec;
import io.ito.arena.segment.Regions;
import io.ito.arena.segment.SegmentFile;
import io.ito.arena.segment.SegmentFormat;
import io.ito.arena.segment.SegmentHeader;
import io.ito.arena.util.Alignment;
import io.ito.arena.util.Sha256;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.agrona.concurrent.EpochNanoClock;

/**
 * Single-writer segment lifecycle: create, append (via {@link GenericAppender}), seal, heartbeat,
 * close. Rows accumulate in place at capacity stride and are never rewound (spec invariant 1).
 *
 * <p>Created via {@link #createSegment}, which lays out the regions, writes the header, embeds the
 * canonical schema and layout descriptor, atomically renames the file into place, then opens batch 0.
 */
public final class SegmentWriter implements AutoCloseable {

    private final SegmentFile file;
    private final ArenaSchema schema;
    private final LayoutDescriptor layout;
    private final Regions regions;
    private final SegmentHeader header;
    private final CatalogCodec catalog;
    private final EpochNanoClock clock;
    private final BatchCursor cursor;
    private boolean closed;

    private SegmentWriter(SegmentFile file, ArenaSchema schema, LayoutDescriptor layout,
                          Regions regions, EpochNanoClock clock) {
        this.file = file;
        this.schema = schema;
        this.layout = layout;
        this.regions = regions;
        this.clock = clock;
        this.header = new SegmentHeader(file.control());
        this.catalog = new CatalogCodec(file.control(), regions.catalogOffset(), regions.catalogCapacity());
        this.cursor = new BatchCursor(schema, layout, file.data(), catalog, header, regions, clock);
    }

    /**
     * Creates a segment able to hold {@code maxBatches} batches.
     *
     * @param path       final segment path (created via a temp file + atomic rename)
     * @param schema     validated schema; must declare a single column family in v1 (invariant 8)
     * @param maxBatches catalog capacity; the writer rotates before opening batch {@code maxBatches}
     * @param epoch      identifies the writing process instance (liveness)
     * @param sequence   monotonic segment sequence number within this table/rotation
     * @param clock      wall-clock nanos source for {@code seal_nanos} (injected for determinism)
     */
    public static SegmentWriter createSegment(Path path, ArenaSchema schema, int maxBatches,
                                              long epoch, long sequence, EpochNanoClock clock) {
        if (maxBatches <= 0) {
            throw new IllegalArgumentException("maxBatches must be positive: " + maxBatches);
        }
        LayoutDescriptor layout = Layouts.compute(schema);
        if (layout.families().size() > 1) {
            throw new IllegalArgumentException(
                    "multi-family write is post-v1; schema declares families " + layout.families());
        }
        byte[] schemaBytes = CanonicalSchema.canonicalBytes(schema);
        byte[] sha = Sha256.hash(schemaBytes);
        int layoutSize = LayoutCodec.encodedSize(layout);
        Regions regions = computeRegions(schemaBytes.length, layoutSize, maxBatches, layout.batchStrideBytes());
        long totalSize = regions.dataOffset() + regions.dataLength();

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp." + epoch + "." + sequence);
        SegmentFile file = SegmentFile.create(tmp, totalSize);
        try {
            SegmentHeader.writeInitial(file.control(), sha, sequence, totalSize, epoch,
                    schema.metadata().batchRows(), layout.batchStrideBytes(), regions);
            file.control().putBytes((int) regions.schemaOffset(), schemaBytes);
            LayoutCodec.encode(layout, file.data(), (int) regions.layoutOffset());
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
            file.renamedTo(path);
        } catch (IOException e) {
            file.close();
            throw new UncheckedIOException("failed to publish segment " + path, e);
        } catch (RuntimeException e) {
            file.close();
            throw e;
        }

        SegmentWriter writer = new SegmentWriter(file, schema, layout, regions, clock);
        writer.cursor.openBatch(0);
        return writer;
    }

    /** Generic, descriptor-driven appender; correctness over speed. */
    public GenericAppender genericAppender() {
        return new GenericAppender(cursor);
    }

    /** Seals the in-progress batch and opens the next (no copy — invariant 1). */
    public void seal() {
        cursor.seal();
    }

    /** Advances the liveness heartbeat. */
    public void heartbeat() {
        header.bumpHeartbeat();
    }

    public ArenaSchema schema() {
        return schema;
    }

    public LayoutDescriptor layoutDescriptor() {
        return layout;
    }

    public Regions regions() {
        return regions;
    }

    /** Exposed for tests to read raw bytes back before the reader exists. */
    public SegmentFile segmentFile() {
        return file;
    }

    /** Package-internal: the shared append engine, handed to {@link RowAppender} subclasses. */
    BatchCursor cursor() {
        return cursor;
    }

    /** Package-internal: the live header, for the typed appender's schema-hash verification. */
    SegmentHeader header() {
        return header;
    }

    /**
     * Seals the trailing in-progress batch, leaving the segment fully self-describing: every batch
     * holding rows carries published zone-map stats, so readers can prune it and an archiver can
     * tell "this segment is finished" from "a writer is still filling it". An empty trailing batch
     * is left in progress (see {@code BatchCursor.sealFinal}).
     *
     * <p>Call before {@link #close()} on the graceful path — {@link io.ito.arena.rotate.RotatingWriter}
     * does this on rotation and shutdown. It is deliberately <em>not</em> folded into {@code close()}:
     * a segment whose last batch stays in progress forever is precisely the signature of a writer
     * that died, and that state must remain both reachable and readable (the golden corpus pins it).
     *
     * <p>Idempotent; appending after this is not supported.
     */
    public void sealFinal() {
        if (closed) {
            return; // the mapping is gone; writing to it would be a use-after-unmap
        }
        cursor.sealFinal();
    }

    /** Releases the mapping. Does not seal — see {@link #sealFinal()}. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        file.close();
    }

    /**
     * Region placement (see docs/segment-format.md "Create-time sizing"). Header is fixed at 4096;
     * schema, descriptor, and catalog are 64-aligned; the data region is page-aligned.
     */
    static Regions computeRegions(int schemaLength, int layoutLength, int maxBatches, long batchStride) {
        long schemaOff = SegmentFormat.HEADER_LENGTH;
        long layoutOff = Alignment.align64(schemaOff + schemaLength);
        long catalogOff = Alignment.align64(layoutOff + layoutLength);
        long catalogLen = (long) maxBatches * SegmentFormat.CATALOG_ENTRY_SIZE;
        long dataOff = Alignment.alignPage(catalogOff + catalogLen);
        long dataLen = (long) maxBatches * batchStride;
        return new Regions(schemaOff, schemaLength, layoutOff, layoutLength, catalogOff, catalogLen, dataOff, dataLen);
    }
}
