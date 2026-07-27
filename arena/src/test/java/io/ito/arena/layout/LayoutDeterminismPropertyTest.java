package io.ito.arena.layout;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * Property test (spec M1): layout generation is deterministic and idempotent. Same schema must
 * always produce an {@code equals} descriptor <em>and</em> byte-identical codec output.
 */
class LayoutDeterminismPropertyTest {

    private static final int SEEDS = 10_000;

    @Test
    void layoutIsDeterministicAcrossManySeeds() {
        for (long seed = 0; seed < SEEDS; seed++) {
            ArenaSchema schema = RandomSchemaGenerator.generate(seed);

            LayoutDescriptor first = Layouts.compute(schema);
            LayoutDescriptor second = Layouts.compute(schema);

            assertThat(second).as("descriptor equality for seed %d", seed).isEqualTo(first);
            assertThat(encode(second)).as("codec byte-identity for seed %d", seed).isEqualTo(encode(first));
        }
    }

    private static byte[] encode(LayoutDescriptor descriptor) {
        byte[] bytes = new byte[LayoutCodec.encodedSize(descriptor)];
        LayoutCodec.encode(descriptor, new UnsafeBuffer(bytes), 0);
        return bytes;
    }
}
