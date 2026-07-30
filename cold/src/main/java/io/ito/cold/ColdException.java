package io.ito.cold;

/** A cold-tier operation failed. Aborts the current run; the next run re-derives what to do. */
public class ColdException extends RuntimeException {

    public ColdException(String message) {
        super(message);
    }

    public ColdException(String message, Throwable cause) {
        super(message, cause);
    }
}
