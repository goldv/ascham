package io.ascham.layout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The hand-written domain enum must agree value-for-value with {@code format/Layout.fbs} — the IDL
 * is the wire-value assignment authority (the C++ reader asserts the same against its flatcc
 * bindings in {@code test_layout_vectors.cpp}).
 */
class PhysicalKindWireTest {

    @Test
    void wireValuesMatchLayoutFbs() {
        assertThat(PhysicalKind.FIXED.wireValue()).isEqualTo((int) io.ascham.flatbuf.PhysicalKind.Fixed);
        assertThat(PhysicalKind.VARLEN.wireValue()).isEqualTo((int) io.ascham.flatbuf.PhysicalKind.Varlen);
        assertThat(PhysicalKind.BOOL_BITMAP.wireValue())
                .isEqualTo((int) io.ascham.flatbuf.PhysicalKind.BoolBitmap);
        assertThat(PhysicalKind.values()).hasSize(io.ascham.flatbuf.PhysicalKind.names.length);
    }
}
