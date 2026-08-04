package io.ascham.segment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class ControlRegionTest {

    private static ControlRegion region() {
        return new ControlRegion(ByteBuffer.allocateDirect(256));
    }

    @Test
    void releaseThenAcquireRoundTrips() {
        ControlRegion region = region();
        region.putLongRelease(64, 0x0123456789ABCDEFL);
        assertThat(region.getLongAcquire(64)).isEqualTo(0x0123456789ABCDEFL);
    }

    @Test
    void plainAccessorsAreLittleEndian() {
        ControlRegion region = region();
        region.putLong(8, 1L);
        // Byte 8 is the least-significant byte in little-endian.
        ByteBuffer view = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        view.putLong(8, 1L);
        assertThat(region.getLong(8)).isEqualTo(1L);
        assertThat(region.getInt(8)).isEqualTo(1);
    }

    @Test
    void bytesRoundTrip() {
        ControlRegion region = region();
        byte[] src = {1, 2, 3, 4, 5, 6, 7, 8};
        region.putBytes(16, src);
        byte[] dst = new byte[8];
        region.getBytes(16, dst);
        assertThat(dst).isEqualTo(src);
    }

    @Test
    void rejectsHeapBuffer() {
        assertThatThrownBy(() -> new ControlRegion(ByteBuffer.allocate(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("direct");
    }

    @Test
    void misalignedOrderedAccessThrows() {
        ControlRegion region = region();
        // Ordered access at a non-8-aligned offset is a runtime error (VarHandle alignment rule).
        assertThatThrownBy(() -> region.getLongAcquire(4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> region.putLongRelease(12, 1L)).isInstanceOf(IllegalStateException.class);
    }
}
