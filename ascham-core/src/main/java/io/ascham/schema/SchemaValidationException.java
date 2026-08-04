package io.ascham.schema;

import java.util.List;

/**
 * Thrown once, at load, carrying <em>every</em> validation error found (not just the first).
 * Validation is strict and total: fail at load, never at append (spec).
 */
public final class SchemaValidationException extends RuntimeException {

    private final transient List<String> errors;

    public SchemaValidationException(List<String> errors) {
        super(format(errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }

    private static String format(List<String> errors) {
        StringBuilder sb = new StringBuilder("schema validation failed with ")
                .append(errors.size())
                .append(errors.size() == 1 ? " error:" : " errors:");
        for (String e : errors) {
            sb.append("\n  - ").append(e);
        }
        return sb.toString();
    }
}
