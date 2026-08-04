package io.ascham.layout;

/**
 * The physical storage class of a column, derived from its Arrow type. This is the axis the layout
 * function branches on, not the logical Arrow type.
 */
public enum PhysicalKind {
    /** Fixed-width primitive: validity bitmap + a {@code width * rows} data buffer. */
    FIXED,
    /** Variable-length ({@code Utf8}/{@code Binary}): validity + int32 offsets + a byte data buffer. */
    VARLEN,
    /** {@code Bool}: validity bitmap + a data bitmap (1 bit per row), not 1 byte per row. */
    BOOL_BITMAP
}
