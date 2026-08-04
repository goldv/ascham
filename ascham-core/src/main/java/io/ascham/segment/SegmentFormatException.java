package io.ascham.segment;

/**
 * Thrown when a segment cannot be interpreted: bad magic, unsupported format version, or a schema
 * hash mismatch at open (spec invariant 7 — a hard failure, never silent misinterpretation).
 */
public final class SegmentFormatException extends RuntimeException {

    public SegmentFormatException(String message) {
        super(message);
    }
}
