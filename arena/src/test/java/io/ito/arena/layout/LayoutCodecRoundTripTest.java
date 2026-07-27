package io.ito.arena.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.schema.ArenaSchema;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * The descriptor codec round-trip {@code decode(encode(d)) == d} is an identity, and
 * {@link LayoutCodec#encodedSize} exactly matches the bytes written (so the descriptor region can
 * be sized before encoding).
 */
class LayoutCodecRoundTripTest {

    @Test
    void roundTripsManySchemas() {
        for (long seed = 0; seed < 2_000; seed++) {
            ArenaSchema schema = RandomSchemaGenerator.generate(seed);
            LayoutDescriptor descriptor = Layouts.compute(schema);

            int size = LayoutCodec.encodedSize(descriptor);
            UnsafeBuffer buffer = new UnsafeBuffer(new byte[size]);
            int written = LayoutCodec.encode(descriptor, buffer, 0);

            assertThat(written).as("encodedSize matches bytes written, seed %d", seed).isEqualTo(size);
            assertThat(LayoutCodec.decode(buffer, 0, size))
                    .as("round-trip identity, seed %d", seed).isEqualTo(descriptor);
        }
    }

    @Test
    void encodesAtNonZeroOffset() {
        LayoutDescriptor descriptor = Layouts.compute(RandomSchemaGenerator.generate(42));
        int size = LayoutCodec.encodedSize(descriptor);
        int offset = 128;
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[offset + size]);

        LayoutCodec.encode(descriptor, buffer, offset);
        assertThat(LayoutCodec.decode(buffer, offset, size)).isEqualTo(descriptor);
    }

    @Test
    void decodeRejectsLengthMismatch() {
        LayoutDescriptor descriptor = Layouts.compute(RandomSchemaGenerator.generate(7));
        int size = LayoutCodec.encodedSize(descriptor);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[size]);
        LayoutCodec.encode(descriptor, buffer, 0);

        assertThatThrownBy(() -> LayoutCodec.decode(buffer, 0, size - 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length mismatch");
    }
}
