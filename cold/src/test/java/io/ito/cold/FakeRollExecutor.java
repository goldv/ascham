package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link RollExecutor} for testing the protocol without a catalog. Records what was
 * asked of it, so tests can assert ordering and idempotence directly rather than inferring them
 * from stored data.
 */
final class FakeRollExecutor implements RollExecutor {

    /** A day the fake has "archived", with the grace clock the test controls. */
    private record Logged(LocalDate day, long rows, List<String> segments, String arenaDir,
                          long committedAtMillis) {
    }

    private final Map<String, List<Logged>> log = new LinkedHashMap<>();
    private final Map<String, Long> dataRows = new LinkedHashMap<>();

    final List<String> calls = new ArrayList<>();
    long nowMillis;
    /** When set, rolling this (table, day) throws — used to test abort-on-first-failure. */
    LocalDate failOnDay;
    String failOnTable; // null = any table

    @Override
    public void ensureTable(String table, ArenaSchema schema, List<String> sortColumns) {
        calls.add("ensureTable:" + table);
    }

    @Override
    public Optional<LocalDate> highestRolledDay(String table) {
        return log.getOrDefault(table, List.of()).stream().map(Logged::day).max(LocalDate::compareTo);
    }

    @Override
    public Optional<String> rolledBy(String table, LocalDate day) {
        return log.getOrDefault(table, List.of()).stream()
                .filter(l -> l.day().equals(day))
                .map(Logged::arenaDir)
                .findFirst();
    }

    @Override
    public boolean hasDataFor(String table, String timeColumn, LocalDate day) {
        return dataRows.containsKey(key(table, day));
    }

    @Override
    public long countDataFor(String table, String timeColumn, LocalDate day) {
        return dataRows.getOrDefault(key(table, day), 0L);
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
        dataRows.put(key(table, day), rows);
        log.computeIfAbsent(table, t -> new ArrayList<>())
                .add(new Logged(day, rows, names, arenaDirOf(segments), nowMillis));
        return rows;
    }

    @Override
    public void logDayOnly(String table, LocalDate day, long rows, List<String> segmentNames) {
        calls.add("logDayOnly:" + table + ":" + day);
        log.computeIfAbsent(table, t -> new ArrayList<>())
                .add(new Logged(day, rows, segmentNames, arenaDir, nowMillis));
    }

    @Override
    public List<ReclaimableDay> reclaimable(String table, Duration grace) {
        List<ReclaimableDay> out = new ArrayList<>();
        for (Logged l : log.getOrDefault(table, List.of())) {
            if (nowMillis - l.committedAtMillis() >= grace.toMillis()) {
                out.add(new ReclaimableDay(l.day(), l.segments(), l.arenaDir()));
            }
        }
        return out;
    }

    @Override
    public void close() {
    }

    /** Set to stamp log entries with a specific arena dir; null means "match whatever asks". */
    String arenaDir;

    private String arenaDirOf(List<Path> segments) {
        if (arenaDir != null) {
            return arenaDir;
        }
        return segments.isEmpty() ? null
                : segments.get(0).getParent().toAbsolutePath().normalize().toString();
    }

    /** Pretends a day's data was committed without its log entry — the crash window. */
    void seedUnloggedData(String table, LocalDate day, long rows) {
        dataRows.put(key(table, day), rows);
    }

    List<String> rollDayCalls() {
        return calls.stream().filter(c -> c.startsWith("rollDay:")).toList();
    }

    private static String key(String table, LocalDate day) {
        return table + "/" + day;
    }
}
