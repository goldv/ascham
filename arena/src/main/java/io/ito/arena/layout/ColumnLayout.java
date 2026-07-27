package io.ito.arena.layout;

/**
 * Byte layout of one column within a batch. All offsets are relative to the batch base, and every
 * buffer base is 64-byte aligned (spec invariant 5). Absolute alignment follows because every batch
 * base is page-aligned.
 *
 * @param name               Arrow field name
 * @param ordinal            column ordinal in the schema
 * @param kind               physical storage kind
 * @param familyId           column-family id (invariant 8; v1 writer supports only family 0)
 * @param elementWidth       fixed element width in bytes, or 0 for VARLEN/BOOL_BITMAP
 * @param validityOffset     batch-relative offset of the validity bitmap
 * @param dataOffset         batch-relative offset of the data buffer (varlen: the byte buffer)
 * @param dataCapacityBytes  byte capacity of the data buffer
 * @param offsetsOffset      batch-relative offset of the int32 offsets buffer, or -1 if not VARLEN
 * @param varlenCapacityBytes byte capacity for varlen data ({@code arena.varlen_bytes}), or 0
 */
public record ColumnLayout(
        String name,
        int ordinal,
        PhysicalKind kind,
        int familyId,
        int elementWidth,
        long validityOffset,
        long dataOffset,
        long dataCapacityBytes,
        long offsetsOffset,
        long varlenCapacityBytes) {

    /** Sentinel {@link #offsetsOffset} for non-varlen columns. */
    public static final long NO_OFFSETS = -1L;

    public boolean isVarlen() {
        return kind == PhysicalKind.VARLEN;
    }
}
