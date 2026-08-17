package io.ascham.samples;

import io.ascham.rotate.RollCycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal {@code --key value} parsing shared by the demo mains, with the defaults in one place. */
final class DemoArgs {

    private final Map<String, String> values = new LinkedHashMap<>();

    private DemoArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            if (key.equals("help")) {
                values.put("help", "true");
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for --" + key);
            }
            values.put(key, args[++i]);
        }
    }

    static DemoArgs parse(String[] args) {
        return new DemoArgs(args);
    }

    boolean helpRequested() {
        return values.containsKey("help");
    }

    /**
     * Where segments go. Prefers {@code /dev/shm/ito} (shared memory, as in production) and falls
     * back to a build directory when it is not writable — a container without a big enough
     * {@code /dev/shm} should still be able to run the demo.
     */
    Path dir() {
        String explicit = values.get("dir");
        if (explicit != null) {
            return Path.of(explicit);
        }
        Path shm = Path.of("/dev/shm/ito");
        try {
            Files.createDirectories(shm);
            if (Files.isWritable(shm)) {
                return shm;
            }
        } catch (Exception e) {
            // fall through to the build directory
        }
        return Path.of("build/segments");
    }

    int rate() {
        return intValue("rate", 50);
    }

    int batchRows() {
        return intValue("batch-rows", 4096);
    }

    int maxBatches() {
        return intValue("max-batches", 512);
    }

    int quotesPerTrade() {
        return intValue("quotes-per-trade", 10);
    }

    long seed() {
        return longValue("seed", 42L);
    }

    int days() {
        return intValue("days", 3);
    }

    int rowsPerDay() {
        return intValue("rows-per-day", 50_000);
    }

    /** Seconds to run before stopping; 0 (the default) means run until interrupted. */
    int seconds() {
        return intValue("seconds", 0);
    }

    /** Cap on consecutive segments per rolled parquet file; below 1 means one file per interval. */
    int maxSegmentsPerFile() {
        return intValue("max-segments-per-file", 0);
    }

    /** The writer's roll cycle — the duration of one segment interval (default 1d). */
    RollCycle rollCycle() {
        String raw = values.get("roll-cycle");
        return raw == null ? RollCycle.parse("1h") : RollCycle.parse(raw);
    }

    String stringValue(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    List<String> symbols() {
        String raw = values.get("symbols");
        if (raw == null) {
            return MarketDataGenerator.DEFAULT_SYMBOLS;
        }
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("--symbols listed no symbols");
        }
        return out;
    }

    private int intValue(String key, int fallback) {
        String raw = values.get(key);
        return raw == null ? fallback : Integer.parseInt(raw);
    }

    private long longValue(String key, long fallback) {
        String raw = values.get(key);
        return raw == null ? fallback : Long.parseLong(raw);
    }
}
