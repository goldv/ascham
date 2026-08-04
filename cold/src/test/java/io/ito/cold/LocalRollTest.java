package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.rotate.RollCycle;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.util.DateTimeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The full roll against a real Iceberg warehouse on the local filesystem — hermetic: no docker, no
 * extensions, no services. This suite is the primary correctness gate for the cold tier; the
 * REST-catalog integration test only smokes the same path against Lakekeeper/MinIO.
 */
class LocalRollTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 25);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 26);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 27);
    private static final long NANOS_PER_DAY = 86_400L * 1_000_000_000L;

    @TempDir
    Path base;
    @TempDir
    Path warehouse;

    private IcebergRollExecutor executor;
    private HadoopCatalog assertionCatalog;

    @AfterEach
    void tearDown() throws IOException {
        if (executor != null) {
            executor.close();
        }
        if (assertionCatalog != null) {
            assertionCatalog.close();
        }
    }

    @Test
    void twoDaysRollWithRowParityWatermarkAndProvenance() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 500);
        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));

        TableRoller.RollResult result = roller.roll("quotes", at(TODAY));

        assertThat(result.intervals()).extracting(TableRoller.IntervalResult::start)
                .containsExactly(at(D1), at(D2));
        assertThat(result.intervals()).extracting(TableRoller.IntervalResult::status)
                .containsOnly(TableRoller.IntervalStatus.ROLLED);
        assertThat(result.totalRows()).isEqualTo(1000);

        Table table = table();
        assertThat(ParquetReadBack.readAll(table)).hasSize(1000);
        assertThat(table.properties())
                .containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH, at(TODAY).toString())
                .containsEntry(IcebergRollExecutor.PROP_ARENA_DIR,
                        base.resolve("quotes").toAbsolutePath().normalize().toString())
                .containsEntry("write.parquet.compression-codec", "zstd")
                .containsEntry("write.target-file-size-bytes", Long.toString(512L << 20));

        // One snapshot per day, each carrying its provenance for the future reclaim util.
        List<Snapshot> snapshots = new ArrayList<>();
        table.snapshots().forEach(snapshots::add);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).summary())
                .containsEntry(IcebergRollExecutor.SUMMARY_DAY, D1.toString())
                .containsEntry(IcebergRollExecutor.SUMMARY_INTERVAL, at(D1) + "/" + at(D2))
                .containsEntry(IcebergRollExecutor.SUMMARY_ROWS, "500")
                .containsEntry(IcebergRollExecutor.SUMMARY_ARENA_DIR,
                        base.resolve("quotes").toAbsolutePath().normalize().toString());
        assertThat(snapshots.get(0).summary().get(IcebergRollExecutor.SUMMARY_SEGMENTS))
                .contains("20260725");

        // The cutover: history ends before TODAY, realtime starts there.
        assertThat(roller.cutover("quotes")).contains(at(TODAY));
    }

    @Test
    void nanosecondTimestampsSurviveTheWholePath() {
        int rows = 200;
        ColdFixtures.writeDays(base, List.of(D1), rows);
        rollWith(config(1)).roll("quotes", at(TODAY));

        // Recompute exactly what the fixture wrote — sub-microsecond digits included.
        long dayStart = at(D1).getEpochSecond() * 1_000_000_000L;
        long step = NANOS_PER_DAY / (rows + 1L);
        List<Long> expected = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            expected.add(dayStart + r * step + (r % 997) + 7);
        }

        List<Long> actual = ParquetReadBack.readAll(table()).stream()
                .map(rec -> DateTimeUtil.nanosFromTimestamp((LocalDateTime) rec.getField("ts")))
                .sorted().toList();
        assertThat(actual).isEqualTo(expected.stream().sorted().toList());
    }

    @Test
    void everyRolledFileIsSortedBySymThenTs() {
        ColdFixtures.writeDays(base, List.of(D1), 300);
        rollWith(config(1)).roll("quotes", at(TODAY));

        Table table = table();
        List<String> files = dataFileLocations(table);
        assertThat(files).isNotEmpty();
        for (String location : files) {
            List<Record> rows = ParquetReadBack.readFile(table, location);
            String prevSym = null;
            long prevTs = Long.MIN_VALUE;
            for (Record row : rows) {
                String sym = (String) row.getField("sym");
                long ts = DateTimeUtil.nanosFromTimestamp((LocalDateTime) row.getField("ts"));
                if (prevSym != null) {
                    assertThat(sym).isGreaterThanOrEqualTo(prevSym);
                    if (sym.equals(prevSym)) {
                        assertThat(ts).isGreaterThanOrEqualTo(prevTs);
                    }
                }
                prevSym = sym;
                prevTs = ts;
            }
        }
    }

    @Test
    void maxSegmentsPerFileCapsConsecutiveSegmentsPerParquetFile() {
        writeOneDayInSegments(D1, 5, 250); // five same-day segments via forced rotations
        rollWith(config(2)).roll("quotes", at(TODAY));

        Table table = table();
        // 5 segments at N=2 → groups of 2+2+1 → 3 data files.
        assertThat(dataFileLocations(table)).hasSize(3);
        assertThat(ParquetReadBack.readAll(table)).hasSize(250);

        // The interval's snapshot names every input segment, whatever the grouping.
        Snapshot snapshot = table.currentSnapshot();
        String segments = snapshot.summary().get(IcebergRollExecutor.SUMMARY_SEGMENTS);
        assertThat(segments.split(",")).hasSize(5);
    }

    @Test
    void withoutMaxSegmentsPerFileAWholeIntervalBecomesOneParquetFile() {
        writeOneDayInSegments(D1, 5, 250);
        rollWith(config()).roll("quotes", at(TODAY));

        Table table = table();
        assertThat(dataFileLocations(table)).hasSize(1);
        assertThat(ParquetReadBack.readAll(table)).hasSize(250);
        String segments = table.currentSnapshot().summary().get(IcebergRollExecutor.SUMMARY_SEGMENTS);
        assertThat(segments.split(",")).hasSize(5);
    }

    @Test
    void subDayIntervalsCommitSeparatelyIntoOneDailyPartition() {
        Instant midnight = at(D1);
        ColdFixtures.writeIntervals(base, RollCycle.parse("4h"),
                List.of(midnight, midnight.plus(Duration.ofHours(4)), midnight.plus(Duration.ofHours(8))),
                100);

        TableRoller.RollResult result =
                rollWith(config()).roll("quotes", midnight.plus(Duration.ofHours(13)));

        assertThat(result.intervals()).extracting(TableRoller.IntervalResult::status)
                .containsOnly(TableRoller.IntervalStatus.ROLLED);
        assertThat(result.intervals()).extracting(TableRoller.IntervalResult::end).containsExactly(
                midnight.plus(Duration.ofHours(4)),
                midnight.plus(Duration.ofHours(8)),
                midnight.plus(Duration.ofHours(12)));

        Table table = table();
        assertThat(ParquetReadBack.readAll(table)).hasSize(300);
        assertThat(table.properties()).containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH,
                midnight.plus(Duration.ofHours(12)).toString());

        // One commit per interval, each with its own provenance, all inside the one day partition.
        List<Snapshot> snapshots = new ArrayList<>();
        table.snapshots().forEach(snapshots::add);
        assertThat(snapshots).hasSize(3);
        assertThat(snapshots.get(1).summary())
                .containsEntry(IcebergRollExecutor.SUMMARY_DAY, D1.toString())
                .containsEntry(IcebergRollExecutor.SUMMARY_INTERVAL,
                        midnight.plus(Duration.ofHours(4)) + "/" + midnight.plus(Duration.ofHours(8)));
        assertThat(dataFileLocations(table)).hasSize(3).allSatisfy(location ->
                assertThat(location).contains("ts_day=2026-07-25"));
    }

    @Test
    void aCycleChangeMidIntervalMergesTheOverlapIntoOneAtomicCommit() {
        // A 4h writer covered [00:00,08:00) in two intervals, then a restart at 06:00 with a 6h
        // cycle declared [06:00,12:00): the second and third intervals overlap and must commit as
        // one unit, or the watermark could advance past rows not yet archived.
        Instant midnight = at(D1);
        ColdFixtures.writeIntervals(base, RollCycle.parse("4h"),
                List.of(midnight, midnight.plus(Duration.ofHours(4))), 100);
        writeRows(RollCycle.parse("6h"), midnight.plus(Duration.ofHours(6)),
                midnight.plus(Duration.ofHours(7)), 100);

        TableRoller.RollResult result =
                rollWith(config()).roll("quotes", midnight.plus(Duration.ofHours(13)));

        assertThat(result.intervals()).hasSize(2);
        assertThat(result.intervals().get(0).start()).isEqualTo(midnight);
        assertThat(result.intervals().get(1).start()).isEqualTo(midnight.plus(Duration.ofHours(4)));
        assertThat(result.intervals().get(1).end()).isEqualTo(midnight.plus(Duration.ofHours(12)));
        assertThat(result.intervals().get(1).segments()).hasSize(2); // 4h + 6h segments, one commit

        Table table = table();
        assertThat(ParquetReadBack.readAll(table)).hasSize(300);
        assertThat(table.properties()).containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH,
                midnight.plus(Duration.ofHours(12)).toString());

        // Rerunning after the merge is still a no-op — no duplicates.
        TableRoller.RollResult rerun =
                rollWith(config()).roll("quotes", midnight.plus(Duration.ofHours(13)));
        assertThat(rerun.intervals()).extracting(TableRoller.IntervalResult::status)
                .containsOnly(TableRoller.IntervalStatus.ALREADY_ROLLED);
        assertThat(ParquetReadBack.readAll(table())).hasSize(300);
    }

    @Test
    void segmentsAlreadyCommittedBelowTheWatermarkAreNotReRolled() {
        // [04:00,08:00) rolls first. Then a restart with a 12h cycle declares [00:00,12:00), which
        // overlaps the committed interval: the merged unit must re-roll only the new segment.
        Instant midnight = at(D1);
        ColdFixtures.writeIntervals(base, RollCycle.parse("4h"),
                List.of(midnight.plus(Duration.ofHours(4))), 100);
        ColdConfig config = config();
        TableRoller roller = new TableRoller(config, executor(config));
        roller.roll("quotes", midnight.plus(Duration.ofHours(9)));
        assertThat(ParquetReadBack.readAll(table())).hasSize(100);

        writeRows(RollCycle.parse("12h"), midnight.plus(Duration.ofHours(9)),
                midnight.plus(Duration.ofHours(10)), 100);
        TableRoller.RollResult result = roller.roll("quotes", midnight.plus(Duration.ofHours(13)));

        assertThat(result.rolled()).singleElement().satisfies(interval -> {
            assertThat(interval.segments()).singleElement().asString().contains("720m");
            assertThat(interval.rows()).isEqualTo(100);
        });
        assertThat(ParquetReadBack.readAll(table())).hasSize(200); // no duplicated [04:00,08:00) rows
        assertThat(table().properties()).containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH,
                midnight.plus(Duration.ofHours(12)).toString());
    }

    @Test
    void aCompletedIntervalRollsWhileTheWriterIsLiveOnTheNextOne() {
        // The point of sub-day cycles: today's finished interval archives while the writer keeps
        // appending to the current one. The rotated-onto segment is the ordering signal — no
        // heartbeat probe is even needed.
        Instant midnight = at(TODAY);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock = new ColdFixtures.MutableClock(midnight.plusSeconds(60));
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.parse("4h"), clock, ColdFixtures.counterNanoClock())) {
            long start = midnight.getEpochSecond() * 1_000_000_000L;
            for (int r = 0; r < 100; r++) {
                ColdFixtures.append(writer, start + r * 1_000L, "AAPL", r);
            }
            clock.set(midnight.plus(Duration.ofHours(4)).plusSeconds(30));
            writer.heartbeat(); // rotates onto [04:00,08:00); the writer stays live there

            TableRoller.RollResult result =
                    rollWith(config()).roll("quotes", clock.instant());

            assertThat(result.rolled()).singleElement().satisfies(interval -> {
                assertThat(interval.start()).isEqualTo(midnight);
                assertThat(interval.end()).isEqualTo(midnight.plus(Duration.ofHours(4)));
            });
            assertThat(ParquetReadBack.readAll(table())).hasSize(100);
        }
    }

    @Test
    void aBareDateWatermarkFromAnOlderTableStillGates() {
        // Tables rolled before interval support carry `ascham.rolled-through=<ISO date>`. It must read
        // back as "that day fully rolled" and be rewritten as an instant by the next commit.
        ColdFixtures.writeDays(base, List.of(D1, D2), 100);
        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));
        roller.roll("quotes", at(D2)); // only D1 is complete at this point

        table().updateProperties()
                .set(IcebergRollExecutor.PROP_ROLLED_THROUGH, D1.toString()) // legacy format
                .commit();

        TableRoller.RollResult result = roller.roll("quotes", at(TODAY));

        assertThat(result.intervals()).extracting(TableRoller.IntervalResult::status).containsExactly(
                TableRoller.IntervalStatus.ALREADY_ROLLED, TableRoller.IntervalStatus.ROLLED);
        assertThat(ParquetReadBack.readAll(table())).hasSize(200); // D1 not duplicated
        // And the commit upgraded the watermark to the instant format.
        assertThat(table().properties()).containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH,
                at(TODAY).toString());
    }

    @Test
    void rerunningARolledBacklogIsANoOp() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 100);
        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));
        roller.roll("quotes", at(TODAY));

        TableRoller.RollResult rerun = roller.roll("quotes", at(TODAY));

        assertThat(rerun.intervals()).extracting(TableRoller.IntervalResult::status)
                .containsOnly(TableRoller.IntervalStatus.ALREADY_ROLLED);
        Table table = table();
        assertThat(ParquetReadBack.readAll(table)).hasSize(200); // zero duplicates
        List<Snapshot> snapshots = new ArrayList<>();
        table.snapshots().forEach(snapshots::add);
        assertThat(snapshots).hasSize(2); // and zero empty snapshots from the rerun
    }

    @Test
    void aLiveWritersIntervalIsLeftNotFrozen() throws Exception {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock = new ColdFixtures.MutableClock(at(D1));
        ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, ColdFixtures.counterNanoClock())) {
            long dayStart = at(D1).getEpochSecond() * 1_000_000_000L;
            for (int r = 0; r < 50; r++) {
                ColdFixtures.append(writer, dayStart + r, "AAPL", r);
            }
            // The writer never rotated off D1 (it is wedged mid-interval) but is demonstrably
            // alive: its heartbeat keeps advancing through the roll's liveness probe.
            heartbeats.scheduleAtFixedRate(writer::heartbeat, 0, 20, TimeUnit.MILLISECONDS);

            TableRoller.RollResult result = rollWith(config(1)).roll("quotes", at(TODAY));

            assertThat(result.intervals()).singleElement()
                    .extracting(TableRoller.IntervalResult::status)
                    .isEqualTo(TableRoller.IntervalStatus.NOT_FROZEN);
            assertThat(table().currentSnapshot()).isNull(); // nothing was committed
        } finally {
            heartbeats.shutdownNow();
        }
    }

    @Test
    void aMisalignedIntervalAbortsTheTableWithNothingCommitted() {
        // Rows written on D1's file interval carry D2 timestamps: name and event time disagree.
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock = new ColdFixtures.MutableClock(at(D1));
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, ColdFixtures.counterNanoClock())) {
            long wrongDay = at(D2).getEpochSecond() * 1_000_000_000L;
            for (int r = 0; r < 10; r++) {
                ColdFixtures.append(writer, wrongDay + r, "AAPL", r);
            }
        }

        assertThatThrownBy(() -> rollWith(config(1)).roll("quotes", at(TODAY)))
                .isInstanceOf(ArenaInventory.IntervalAlignmentException.class);

        assertThat(table().currentSnapshot()).isNull();
        assertThat(table().properties()).doesNotContainKey(IcebergRollExecutor.PROP_ROLLED_THROUGH);
    }

    @Test
    void anUnknownTableIsRejectedWithoutInventingADirectory() {
        ColdFixtures.writeDays(base, List.of(D1), 10);
        assertThatThrownBy(() -> rollWith(config(1)).roll("quotse", at(TODAY)))
                .isInstanceOf(ColdException.class)
                .hasMessageContaining("no arena table directory");
        assertThat(base.resolve("quotse")).doesNotExist();
    }

    @Test
    void aTableOwnedByAnotherArenaIsRefusedBeforeAnyIntervalIsTouched() {
        ColdFixtures.writeDays(base, List.of(D1), 10);
        rollWith(config(1)).roll("quotes", at(TODAY));

        // Same warehouse, different arena: a second deployment pointed at the same destination.
        Path otherArena = base.resolve("other-arena");
        ColdFixtures.writeDays(otherArena, List.of(D2), 10);
        ColdConfig foreign = ColdConfig.builder()
                .arenaBaseDir(otherArena)
                .destination(warehouse.toString())
                .sortColumns(Map.of("quotes", List.of("sym", "ts")))
                .build();
        try (IcebergRollExecutor foreignExecutor = new IcebergRollExecutor(foreign)) {
            assertThatThrownBy(() -> new TableRoller(foreign, foreignExecutor).roll("quotes", at(TODAY)))
                    .isInstanceOf(ColdException.class)
                    .hasMessageContaining("different arena");
        }
        assertThat(ParquetReadBack.readAll(table())).hasSize(10); // and nothing was duplicated
    }

    @Test
    void uncommittedFilesFromACrashedRunDoNotAffectCorrectness() throws IOException {
        // A run that dies between writing parquet and committing leaves orphan files in the
        // warehouse. They are invisible to the table; the rerun rolls the interval cleanly.
        ColdFixtures.writeDays(base, List.of(D1, D2), 100);
        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));
        roller.roll("quotes", at(TODAY)); // D1+D2 committed

        // Simulate the crash artifact: a stray parquet file in the table's data directory.
        Table table = table();
        Path committed = localPath(dataFileLocations(table).get(0));
        Files.copy(committed, committed.getParent().resolve("orphan-from-crashed-run.parquet"));

        TableRoller.RollResult rerun = roller.roll("quotes", at(TODAY));
        assertThat(rerun.intervals()).extracting(TableRoller.IntervalResult::status)
                .containsOnly(TableRoller.IntervalStatus.ALREADY_ROLLED);
        assertThat(ParquetReadBack.readAll(table())).hasSize(200); // the orphan is not visible
    }

    @Test
    void aFailedIntervalCommitsNothingAndTheRerunRollsItCleanly() {
        // Two days pending, but D2's segments are misaligned — the run aborts on D2 after D1
        // committed. The rerun must treat D1 as done and still refuse D2.
        ColdFixtures.writeDays(base, List.of(D1), 100);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock = new ColdFixtures.MutableClock(at(D2));
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 2L,
                RollCycle.DAILY, clock, ColdFixtures.counterNanoClock())) {
            long wrongDay = at(TODAY).getEpochSecond() * 1_000_000_000L;
            ColdFixtures.append(writer, wrongDay, "AAPL", 1);
        }

        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));
        assertThatThrownBy(() -> roller.roll("quotes", at(TODAY)))
                .isInstanceOf(ArenaInventory.IntervalAlignmentException.class);

        // D1 landed before the abort; the watermark stands at D1's end and only there.
        assertThat(table().properties())
                .containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH, at(D2).toString());
        assertThat(ParquetReadBack.readAll(table())).hasSize(100);

        assertThatThrownBy(() -> roller.roll("quotes", at(TODAY)))
                .isInstanceOf(ArenaInventory.IntervalAlignmentException.class);
        assertThat(ParquetReadBack.readAll(table())).hasSize(100); // still exactly one D1
    }

    // --- helpers ---

    private static Instant at(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private ColdConfig config(int maxSegmentsPerFile) {
        return builderWithDefaults().maxSegmentsPerFile(maxSegmentsPerFile).build();
    }

    /** maxSegmentsPerFile at its no-cap default: the whole interval rolls into one parquet file. */
    private ColdConfig config() {
        return builderWithDefaults().build();
    }

    private ColdConfig.Builder builderWithDefaults() {
        return ColdConfig.builder()
                .arenaBaseDir(base)
                .destination(warehouse.toString())
                .sortColumns(Map.of("quotes", List.of("sym", "ts")))
                .livenessProbe(Duration.ofMillis(150));
    }

    private IcebergRollExecutor executor(ColdConfig config) {
        if (executor == null) {
            executor = new IcebergRollExecutor(config);
        }
        return executor;
    }

    private TableRoller rollWith(ColdConfig config) {
        return new TableRoller(config, executor(config));
    }

    private Table table() {
        if (assertionCatalog == null) {
            assertionCatalog = new HadoopCatalog(new Configuration(), warehouse.toString());
        }
        return assertionCatalog.loadTable(TableIdentifier.of("ito", "quotes"));
    }

    private static List<String> dataFileLocations(Table table) {
        List<String> out = new ArrayList<>();
        try (var tasks = table.newScan().planFiles()) {
            for (FileScanTask task : tasks) {
                out.add(task.file().location());
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    private static Path localPath(String location) {
        return location.startsWith("file:")
                ? Path.of(java.net.URI.create(location))
                : Path.of(location);
    }

    /** One day split into {@code segments} same-day segments of {@code rows} total. */
    private void writeOneDayInSegments(LocalDate day, int segments, int rows) {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock = new ColdFixtures.MutableClock(at(day));
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                RollCycle.DAILY, clock, ColdFixtures.counterNanoClock())) {
            long dayStart = at(day).getEpochSecond() * 1_000_000_000L;
            int perSegment = rows / segments;
            for (int r = 0; r < rows; r++) {
                if (r > 0 && r % perSegment == 0 && r / perSegment < segments) {
                    writer.rotate();
                }
                ColdFixtures.append(writer, dayStart + r * 1_000L,
                        ColdFixtures.SYMBOLS[r % ColdFixtures.SYMBOLS.length], r);
            }
        }
        assertThat(dir.list()).hasSize(segments);
    }

    /** Opens a fresh writer with {@code cycle} at {@code clockAt} and appends {@code rows} rows
     *  spread over [{@code from}, {@code from}+1h). */
    private void writeRows(RollCycle cycle, Instant clockAt, Instant from, int rows) {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        long epoch = dir.latestEpoch().orElse(0L) + 1;
        ColdFixtures.MutableClock clock = new ColdFixtures.MutableClock(clockAt);
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096,
                epoch, cycle, clock, ColdFixtures.counterNanoClock())) {
            long start = from.getEpochSecond() * 1_000_000_000L;
            long step = Duration.ofHours(1).toNanos() / (rows + 1L);
            for (int r = 0; r < rows; r++) {
                ColdFixtures.append(writer, start + r * step,
                        ColdFixtures.SYMBOLS[r % ColdFixtures.SYMBOLS.length], r);
            }
        }
    }
}
