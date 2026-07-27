package io.ito.arena.segment;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The sole cross-process ordering surface. Wraps a direct {@link ByteBuffer} over the control region
 * (header + schema + descriptor + catalog) and exposes {@code getLongAcquire}/{@code putLongRelease}
 * via one {@link VarHandle} from {@link MethodHandles#byteBufferViewVarHandle}, plus plain get/put.
 *
 * <p>All cross-process ordering — the catalog {@code length}, the header heartbeat, and
 * {@code active_batch_count} — goes through the acquire/release methods here and nowhere else (spec:
 * "VarHandle with explicit getAcquire/setRelease for all cross-process ordering"). Confining ordered
 * access to this class keeps the memory-model reasoning in one place and makes any future swap of the
 * mechanism a one-class change.
 *
 * <p>Ordered access requires 8-byte-aligned offsets and throws otherwise; every ordered field in the
 * format sits at an 8-aligned offset (header fields; 64-byte catalog entries). The constructor probes
 * alignment at offset 0 so a misconfigured mapping fails fast rather than at first publish.
 */
public final class ControlRegion {

    private static final VarHandle LONG =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final ByteBuffer buffer;

    public ControlRegion(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("control region must be a direct buffer");
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        this.buffer = buffer;
        // Probe: an aligned acquire at offset 0. Throws if the mapping base is not 8-aligned.
        long ignored = (long) LONG.getAcquire(buffer, 0);
        if (ignored == Long.MIN_VALUE) {
            // Never true in practice; defeats dead-code elimination of the probe read.
            throw new AssertionError();
        }
    }

    // --- Ordered access (the only memory-ordering primitives in the module). ---

    public long getLongAcquire(int offset) {
        return (long) LONG.getAcquire(buffer, offset);
    }

    public void putLongRelease(int offset, long value) {
        LONG.setRelease(buffer, offset, value);
    }

    // --- Plain access. ---

    public long getLong(int offset) {
        return buffer.getLong(offset);
    }

    public void putLong(int offset, long value) {
        buffer.putLong(offset, value);
    }

    public int getInt(int offset) {
        return buffer.getInt(offset);
    }

    public void putInt(int offset, int value) {
        buffer.putInt(offset, value);
    }

    public void getBytes(int offset, byte[] dst) {
        buffer.get(offset, dst);
    }

    public void putBytes(int offset, byte[] src) {
        buffer.put(offset, src);
    }

    public int capacity() {
        return buffer.capacity();
    }
}
