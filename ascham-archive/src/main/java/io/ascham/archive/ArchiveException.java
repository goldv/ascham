package io.ascham.archive;

/** A cold-tier operation failed. Aborts the current run; the next run re-derives what to do. */
public class ArchiveException extends RuntimeException {

    public ArchiveException(String message) {
        super(message);
    }

    public ArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
