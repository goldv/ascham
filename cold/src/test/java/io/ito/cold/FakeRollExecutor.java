package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link RollExecutor} for testing the protocol without a warehouse. Records what was
 * asked of it, so tests can assert ordering and idempotence directly rather than inferring them
 * from stored data.
 */
final class FakeRollExecutor implements RollExecutor {

    private record Rolled(Instant end, long rows, List<String> segments) {
    }

    private final Map<String, List<Rolled>> rolled = new LinkedHashMap<>();

    final List<String> calls = new ArrayList<>();
    /** When set, rolling the interval starting here throws — used to test abort-on-first-failure. */
    Instant failOnIntervalStart;
    String failOnTable; // null = any table
    /** When set, ensureTable throws for this table — the foreign-arena ownership refusal. */
    String foreignArenaTable;

    @Override
    public void ensureTable(String table, ArenaSchema schema, List<String> sortColumns) {
        calls.add("ensureTable:" + table);
        if (table.equals(foreignArenaTable)) {
            throw new ColdException("historical table " + table
                    + " belongs to a different arena (injected)");
        }
    }

    @Override
    public Optional<Instant> rolledThrough(String table) {
        return rolled.getOrDefault(table, List.of()).stream().map(Rolled::end).max(Instant::compareTo);
    }

    @Override
    public long rollInterval(String table, ArenaSchema schema, Instant intervalStart, Instant intervalEnd,
                             List<Path> segments, List<String> sortColumns) {
        calls.add("rollInterval:" + table + ":" + intervalStart);
        if (intervalStart.equals(failOnIntervalStart)
                && (failOnTable == null || failOnTable.equals(table))) {
            throw new ColdException("injected failure rolling " + intervalStart);
        }
        List<String> names = segments.stream().map(p -> p.getFileName().toString()).toList();
        long rows = segments.size() * 10L;
        rolled.computeIfAbsent(table, t -> new ArrayList<>()).add(new Rolled(intervalEnd, rows, names));
        return rows;
    }

    @Override
    public void close() {
    }

    /** Marks the table as rolled through {@code end}, as if an earlier run committed it. */
    void seedRolledThrough(String table, Instant end) {
        rolled.computeIfAbsent(table, t -> new ArrayList<>()).add(new Rolled(end, 0, List.of()));
    }

    List<String> rollIntervalCalls() {
        return calls.stream().filter(c -> c.startsWith("rollInterval:")).toList();
    }
}
