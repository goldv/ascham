package io.ascham.archive.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Human-friendly option value converters. A {@link TypeConversionException} is picocli's usage
 * error: message plus usage help on stderr, exit code 2.
 */
final class CliConverters {

    private CliConverters() {
    }

    /**
     * Expands a leading {@code ~} to the user's home directory. The values these options usually
     * arrive through — Gradle's {@code --args="..."} — are quoted, so the shell never expands the
     * tilde itself and the raw string would otherwise become a relative path to a literal
     * {@code ~} directory under the Gradle project.
     */
    static String expandTilde(String value) {
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return System.getProperty("user.home") + value.substring(1);
        }
        if (value.startsWith("~")) {
            throw new TypeConversionException(
                    "cannot expand '" + value + "': ~user paths are not supported, use an absolute path");
        }
        return value;
    }

    /** A filesystem path with a leading {@code ~} expanded. */
    static final class TildePath implements ITypeConverter<Path> {
        @Override
        public Path convert(String value) {
            return Path.of(expandTilde(value.strip()));
        }
    }

    /** A destination string with a leading {@code ~} expanded; URLs pass through untouched. */
    static final class TildeString implements ITypeConverter<String> {
        @Override
        public String convert(String value) {
            return expandTilde(value.strip());
        }
    }

    /** {@code 1048576}, {@code 512m}, {@code 1G}, {@code 1gb} — suffixes are binary multiples,
     *  matching the library's {@code 512 << 20} default. */
    static final class ByteSize implements ITypeConverter<Long> {
        private static final Pattern FORM = Pattern.compile("(?i)(\\d+)([kmgt]?)b?");

        @Override
        public Long convert(String value) {
            Matcher m = FORM.matcher(value.strip());
            if (!m.matches()) {
                throw new TypeConversionException(
                        "'" + value + "' is not a size (expected bytes or a k/m/g/t suffix, e.g. 512m)");
            }
            int shift = switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "" -> 0;
                case "k" -> 10;
                case "m" -> 20;
                case "g" -> 30;
                default -> 40; // t
            };
            try {
                long base = Long.parseLong(m.group(1));
                long result = base << shift;
                if (result >> shift != base) {
                    throw new NumberFormatException("overflow");
                }
                return result;
            } catch (NumberFormatException e) {
                throw new TypeConversionException("'" + value + "' is out of range for a byte size");
            }
        }
    }

    /** {@code 500ms}, {@code 5s}, {@code 2m}, {@code 1h}, {@code 1d}, or ISO-8601 ({@code PT30S}) —
     *  picocli's built-in Duration support is ISO-only. */
    static final class HumanDuration implements ITypeConverter<Duration> {
        private static final Pattern FORM = Pattern.compile("(?i)(\\d+)(ms|s|m|h|d)");

        @Override
        public Duration convert(String value) {
            String v = value.strip();
            if (v.regionMatches(true, 0, "P", 0, 1) || v.regionMatches(true, 0, "-P", 0, 2)) {
                try {
                    return Duration.parse(v);
                } catch (java.time.format.DateTimeParseException e) {
                    throw new TypeConversionException("'" + value + "' is not an ISO-8601 duration");
                }
            }
            Matcher m = FORM.matcher(v);
            if (!m.matches()) {
                throw new TypeConversionException(
                        "'" + value + "' is not a duration (expected e.g. 500ms, 5s, 2m, 1h, 1d)");
            }
            long amount = Long.parseLong(m.group(1));
            return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount); // d
            };
        }
    }
}
