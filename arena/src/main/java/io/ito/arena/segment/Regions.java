package io.ito.arena.segment;

/**
 * Segment-relative offsets and lengths of the variable regions, as recorded in the header region
 * table. The family-watermarks region is reserved in v1 (offset and length both 0; invariant 8).
 */
public record Regions(
        long schemaOffset, long schemaLength,
        long layoutOffset, long layoutLength,
        long catalogOffset, long catalogLength,
        long dataOffset, long dataLength) {

    /** Number of fixed-size catalog entries the catalog region holds. */
    public int catalogCapacity() {
        return (int) (catalogLength / SegmentFormat.CATALOG_ENTRY_SIZE);
    }
}
