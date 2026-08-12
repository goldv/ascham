package io.ascham.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ascham.schema.ArenaSchema;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * The descriptor codec round-trip {@code decode(encode(d)) == d} is an identity, at any region
 * offset, and non-descriptor bytes are rejected up front by the {@code ALD2} identifier check.
 */
class LayoutCodecRoundTripTest {

    @Test
    void roundTripsManySchemas() {
        for (long seed = 0; seed < 2_000; seed++) {
            ArenaSchema schema = RandomSchemaGenerator.generate(seed);
            LayoutDescriptor descriptor = Layouts.compute(schema);

            byte[] bytes = LayoutCodec.encode(descriptor);
            assertThat(LayoutCodec.decode(new UnsafeBuffer(bytes), 0, bytes.length))
                    .as("round-trip identity, seed %d", seed).isEqualTo(descriptor);
        }
    }

    @Test
    void decodesAtNonZeroOffset() {
        LayoutDescriptor descriptor = Layouts.compute(RandomSchemaGenerator.generate(42));
        byte[] bytes = LayoutCodec.encode(descriptor);
        int offset = 128;
        byte[] region = new byte[offset + bytes.length];
        System.arraycopy(bytes, 0, region, offset, bytes.length);

        assertThat(LayoutCodec.decode(new UnsafeBuffer(region), offset, bytes.length))
                .isEqualTo(descriptor);
    }

    @Test
    void decodeRejectsForeignBytes() {
        byte[] junk = new byte[64]; // zeroed — no ALD2 identifier at bytes 4..8
        assertThatThrownBy(() -> LayoutCodec.decode(new UnsafeBuffer(junk), 0, junk.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ALD2");
    }

    @Test
    void decodeRejectsTooShortRegion() {
        assertThatThrownBy(() -> LayoutCodec.decode(new UnsafeBuffer(new byte[4]), 0, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }
}
