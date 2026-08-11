// GENERATED from spec/format-manifest.toml (sha256 c85825dcb549) by spec/generate_format.py — DO NOT EDIT.
// Regenerate with: python3 spec/generate_format.py --lang java --repo .
package io.ascham.layout;

/**
 * The physical storage class of a column, derived from its Arrow type. This is the axis the layout
 * function branches on, not the logical Arrow type.
 *
 * <p>The wire value is what {@code LayoutCodec} writes into every segment's layout descriptor. It
 * is format contract, assigned explicitly here (never the enum ordinal) so reordering or inserting
 * declarations cannot silently change the format.
 */
public enum PhysicalKind {
    /** Fixed-width primitive: validity bitmap + a {@code width * rows} data buffer. */
    FIXED(0),
    /** Variable-length ({@code Utf8}/{@code Binary}): validity + int32 offsets + a byte data buffer. */
    VARLEN(1),
    /** {@code Bool}: validity bitmap + a data bitmap (1 bit per row), not 1 byte per row. */
    BOOL_BITMAP(2);

    private final int wireValue;

    PhysicalKind(int wireValue) {
        this.wireValue = wireValue;
    }

    /** The value written to / read from the layout descriptor. Format contract. */
    public int wireValue() {
        return wireValue;
    }

    /** Resolves a descriptor {@code kind} wire value; throws on an unknown value. */
    public static PhysicalKind fromWire(int value) {
        for (PhysicalKind kind : values()) {
            if (kind.wireValue == value) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown PhysicalKind wire value: " + value);
    }
}
