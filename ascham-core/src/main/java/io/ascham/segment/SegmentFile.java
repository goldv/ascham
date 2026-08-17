package io.ascham.segment;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A memory-mapped segment file. The whole file is mapped once as a direct buffer; ordered access
 * goes through {@link #control()} (VarHandle, {@link ControlRegion}) and plain/bulk data access
 * through {@link #data()} (Agrona {@link UnsafeBuffer} over the same memory).
 *
 * <p>v1 caps a segment at {@link Integer#MAX_VALUE} bytes: Agrona 2.x removed
 * {@code MappedResizeableBuffer} and {@code UnsafeBuffer} is int-capacity, so larger tables are
 * handled by capacity-triggered rotation rather than a single &gt;2 GB mapping. This is a Java
 * implementation limit, not a format rule (format/segment-format.md, "Implementing the contract"),
 * and it lives here and nowhere else, so a large-mapping backend can drop in later.
 */
public final class SegmentFile implements AutoCloseable {

    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final MappedByteBuffer mapped;
    private final ControlRegion control;
    private final UnsafeBuffer data;
    private final long size;
    private Path path;

    private SegmentFile(RandomAccessFile raf, FileChannel channel, MappedByteBuffer mapped,
                        UnsafeBuffer data, long size, Path path) {
        this.raf = raf;
        this.channel = channel;
        this.mapped = mapped;
        this.control = new ControlRegion(mapped);
        this.data = data;
        this.size = size;
        this.path = path;
    }

    /** Creates and maps a new read/write segment file of {@code size} bytes. */
    public static SegmentFile create(Path path, long size) {
        requireMappable(size);
        try {
            RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
            raf.setLength(size);
            FileChannel channel = raf.getChannel();
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
            UnsafeBuffer data = new UnsafeBuffer(mapped);
            return new SegmentFile(raf, channel, mapped, data, size, path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create segment " + path, e);
        }
    }

    /** Maps an existing segment file read-only (reader side). */
    public static SegmentFile openReadOnly(Path path) {
        try {
            RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
            long size = raf.length();
            requireMappable(size);
            FileChannel channel = raf.getChannel();
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            UnsafeBuffer data = new UnsafeBuffer(mapped);
            return new SegmentFile(raf, channel, mapped, data, size, path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open segment " + path, e);
        }
    }

    public ControlRegion control() {
        return control;
    }

    /** Whole-file plain view; data-region access uses absolute (segment-relative) offsets. */
    public UnsafeBuffer data() {
        return data;
    }

    public long size() {
        return size;
    }

    public Path path() {
        return path;
    }

    /** Records the file's post-rename path (the mapping itself is by fd and survives the rename). */
    public void renamedTo(Path newPath) {
        this.path = newPath;
    }

    @Override
    public void close() {
        IoUtil.unmap(mapped);
        try {
            channel.close();
            raf.close();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close segment " + path, e);
        }
    }

    private static void requireMappable(long size) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "v1 segment capacity is capped at 2 GB (" + size + " requested); rotate to span more.");
        }
    }
}
