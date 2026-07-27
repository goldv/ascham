package io.ito.arena.schema;

/**
 * Thrown by {@link TypeProfile} when a field's Arrow type is outside the v1 supported profile.
 * Carries the column name so {@link SchemaValidator} can aggregate it with other errors.
 */
public final class UnsupportedTypeException extends RuntimeException {

    private final String column;

    public UnsupportedTypeException(String column, String reason) {
        super("column '" + column + "': " + reason);
        this.column = column;
    }

    public String column() {
        return column;
    }
}
