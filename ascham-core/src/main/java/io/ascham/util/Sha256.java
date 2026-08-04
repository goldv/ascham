package io.ascham.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the canonical schema bytes. Stored in the segment header and re-verified at open:
 * a schema hash mismatch is a hard failure (spec invariant 7 — a reader misinterpreting a layout
 * produces plausible garbage, the worst possible failure mode).
 */
public final class Sha256 {

    /** Digest length in bytes. */
    public static final int LENGTH = 32;

    private Sha256() {
    }

    public static byte[] hash(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required algorithm on every conformant JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
