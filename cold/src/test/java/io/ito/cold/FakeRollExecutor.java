package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.LocalDate;
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

    private record Rolled(LocalDate day, long rows, List<String> segments) {
    }

    private final Map<String, List<Rolled>> rolled = new LinkedHashMap<>();

    final List<String> calls = new ArrayList<>();
    /** When set, rolling this (table, day) throws — used to test abort-on-first-failure. */
    LocalDate failOnDay;
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
    public Optional<LocalDate> highestRolledDay(String table) {
        return rolled.getOrDefault(table, List.of()).stream().map(Rolled::day).max(LocalDate::compareTo);
    }

    @Override
    public long rollDay(String table, ArenaSchema schema, LocalDate day, List<Path> segments,
                        List<String> sortColumns) {
        calls.add("rollDay:" + table + ":" + day);
        if (day.equals(failOnDay) && (failOnTable == null || failOnTable.equals(table))) {
            throw new ColdException("injected failure rolling " + day);
        }
        List<String> names = segments.stream().map(p -> p.getFileName().toString()).toList();
        long rows = segments.size() * 10L;
        rolled.computeIfAbsent(table, t -> new ArrayList<>()).add(new Rolled(day, rows, names));
        return rows;
    }

    @Override
    public void close() {
    }

    /** Marks a day as already committed, as if an earlier run rolled it. */
    void seedRolledDay(String table, LocalDate day) {
        rolled.computeIfAbsent(table, t -> new ArrayList<>()).add(new Rolled(day, 0, List.of()));
    }

    List<String> rollDayCalls() {
        return calls.stream().filter(c -> c.startsWith("rollDay:")).toList();
    }
}
