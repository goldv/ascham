package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.rotate.DailyRotationPolicy;
import io.ito.arena.rotate.RotatingWriter;
import io.ito.arena.rotate.SegmentDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

        TableRoller.RollResult result = roller.roll("quotes", TODAY);

        assertThat(result.days()).extracting(TableRoller.DayResult::day).containsExactly(D1, D2);
        assertThat(result.days()).extracting(TableRoller.DayResult::status)
                .containsOnly(TableRoller.DayStatus.ROLLED);
        assertThat(result.totalRows()).isEqualTo(1000);

        Table table = table();
        assertThat(ParquetReadBack.readAll(table)).hasSize(1000);
        assertThat(table.properties())
                .containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH, D2.toString())
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
                .containsEntry(IcebergRollExecutor.SUMMARY_ROWS, "500")
                .containsEntry(IcebergRollExecutor.SUMMARY_ARENA_DIR,
                        base.resolve("quotes").toAbsolutePath().normalize().toString());
        assertThat(snapshots.get(0).summary().get(IcebergRollExecutor.SUMMARY_SEGMENTS))
                .contains("20260725");

        // The cutover: history ends before TODAY, realtime starts there.
        assertThat(roller.cutoverDay("quotes")).contains(TODAY);
    }

    @Test
    void nanosecondTimestampsSurviveTheWholePath() {
        int rows = 200;
        ColdFixtures.writeDays(base, List.of(D1), rows);
        rollWith(config(1)).roll("quotes", TODAY);

        // Recompute exactly what the fixture wrote — sub-microsecond digits included.
        long dayStart = D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
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
        rollWith(config(1)).roll("quotes", TODAY);

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
    void segmentsPerFileGroupsConsecutiveSegmentsIntoOneParquetFile() {
        writeOneDayInSegments(D1, 5, 250); // five same-day segments via forced rotations
        rollWith(config(2)).roll("quotes", TODAY);

        Table table = table();
        // 5 segments at N=2 → groups of 2+2+1 → 3 data files.
        assertThat(dataFileLocations(table)).hasSize(3);
        assertThat(ParquetReadBack.readAll(table)).hasSize(250);

        // The day's snapshot names every input segment, whatever the grouping.
        Snapshot snapshot = table.currentSnapshot();
        String segments = snapshot.summary().get(IcebergRollExecutor.SUMMARY_SEGMENTS);
        assertThat(segments.split(",")).hasSize(5);
    }

    @Test
    void rerunningARolledBacklogIsANoOp() {
        ColdFixtures.writeDays(base, List.of(D1, D2), 100);
        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));
        roller.roll("quotes", TODAY);

        TableRoller.RollResult rerun = roller.roll("quotes", TODAY);

        assertThat(rerun.days()).extracting(TableRoller.DayResult::status)
                .containsOnly(TableRoller.DayStatus.ALREADY_ROLLED);
        Table table = table();
        assertThat(ParquetReadBack.readAll(table)).hasSize(200); // zero duplicates
        List<Snapshot> snapshots = new ArrayList<>();
        table.snapshots().forEach(snapshots::add);
        assertThat(snapshots).hasSize(2); // and zero empty snapshots from the rerun
    }

    @Test
    void aLiveWritersDayIsLeftNotFrozen() throws Exception {
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock =
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant());
        ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(), clock, ColdFixtures.counterNanoClock())) {
            long dayStart = D1.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
            for (int r = 0; r < 50; r++) {
                ColdFixtures.append(writer, dayStart + r, "AAPL", r);
            }
            // The writer never rotated off D1 (it is wedged mid-day) but is demonstrably alive:
            // its heartbeat keeps advancing through the roll's liveness probe.
            heartbeats.scheduleAtFixedRate(writer::heartbeat, 0, 20, TimeUnit.MILLISECONDS);

            TableRoller.RollResult result = rollWith(config(1)).roll("quotes", TODAY);

            assertThat(result.days()).singleElement()
                    .extracting(TableRoller.DayResult::status)
                    .isEqualTo(TableRoller.DayStatus.NOT_FROZEN);
            assertThat(table().currentSnapshot()).isNull(); // nothing was committed
        } finally {
            heartbeats.shutdownNow();
        }
    }

    @Test
    void aMisalignedDayAbortsTheTableWithNothingCommitted() {
        // Rows written on D1's file-day carry D2 timestamps: file name and event time disagree.
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock =
                new ColdFixtures.MutableClock(D1.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(), clock, ColdFixtures.counterNanoClock())) {
            long wrongDay = D2.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
            for (int r = 0; r < 10; r++) {
                ColdFixtures.append(writer, wrongDay + r, "AAPL", r);
            }
        }

        assertThatThrownBy(() -> rollWith(config(1)).roll("quotes", TODAY))
                .isInstanceOf(ArenaInventory.DayAlignmentException.class);

        assertThat(table().currentSnapshot()).isNull();
        assertThat(table().properties()).doesNotContainKey(IcebergRollExecutor.PROP_ROLLED_THROUGH);
    }

    @Test
    void anUnknownTableIsRejectedWithoutInventingADirectory() {
        ColdFixtures.writeDays(base, List.of(D1), 10);
        assertThatThrownBy(() -> rollWith(config(1)).roll("quotse", TODAY))
                .isInstanceOf(ColdException.class)
                .hasMessageContaining("no arena table directory");
        assertThat(base.resolve("quotse")).doesNotExist();
    }

    @Test
    void aTableOwnedByAnotherArenaIsRefusedBeforeAnyDayIsTouched() {
        ColdFixtures.writeDays(base, List.of(D1), 10);
        rollWith(config(1)).roll("quotes", TODAY);

        // Same warehouse, different arena: a second deployment pointed at the same destination.
        Path otherArena = base.resolve("other-arena");
        ColdFixtures.writeDays(otherArena, List.of(D2), 10);
        ColdConfig foreign = ColdConfig.builder()
                .arenaBaseDir(otherArena)
                .destination(warehouse.toString())
                .sortColumns(Map.of("quotes", List.of("sym", "ts")))
                .build();
        try (IcebergRollExecutor foreignExecutor = new IcebergRollExecutor(foreign)) {
            assertThatThrownBy(() -> new TableRoller(foreign, foreignExecutor).roll("quotes", TODAY))
                    .isInstanceOf(ColdException.class)
                    .hasMessageContaining("different arena");
        }
        assertThat(ParquetReadBack.readAll(table())).hasSize(10); // and nothing was duplicated
    }

    @Test
    void uncommittedFilesFromACrashedRunDoNotAffectCorrectness() throws IOException {
        // A run that dies between writing parquet and committing leaves orphan files in the
        // warehouse. They are invisible to the table; the rerun rolls the day cleanly.
        ColdFixtures.writeDays(base, List.of(D1, D2), 100);
        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));
        roller.roll("quotes", TODAY); // D1+D2 committed

        // Simulate the crash artifact: a stray parquet file in the table's data directory.
        Table table = table();
        Path committed = localPath(dataFileLocations(table).get(0));
        Files.copy(committed, committed.getParent().resolve("orphan-from-crashed-run.parquet"));

        TableRoller.RollResult rerun = roller.roll("quotes", TODAY);
        assertThat(rerun.days()).extracting(TableRoller.DayResult::status)
                .containsOnly(TableRoller.DayStatus.ALREADY_ROLLED);
        assertThat(ParquetReadBack.readAll(table())).hasSize(200); // the orphan is not visible
    }

    @Test
    void aFailedDayCommitsNothingAndTheRerunRollsItCleanly() {
        // Two days pending, but D2's segments are misaligned — the run aborts on D2 after D1
        // committed. The rerun must treat D1 as done and still refuse D2.
        ColdFixtures.writeDays(base, List.of(D1), 100);
        SegmentDirectory dir = new SegmentDirectory(base, "quotes");
        ColdFixtures.MutableClock clock =
                new ColdFixtures.MutableClock(D2.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 2L,
                new DailyRotationPolicy(), clock, ColdFixtures.counterNanoClock())) {
            long wrongDay = TODAY.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond()
                    * 1_000_000_000L;
            ColdFixtures.append(writer, wrongDay, "AAPL", 1);
        }

        ColdConfig config = config(1);
        TableRoller roller = new TableRoller(config, executor(config));
        assertThatThrownBy(() -> roller.roll("quotes", TODAY))
                .isInstanceOf(ArenaInventory.DayAlignmentException.class);

        // D1 landed before the abort; the watermark stands at D1 and only D1.
        assertThat(table().properties())
                .containsEntry(IcebergRollExecutor.PROP_ROLLED_THROUGH, D1.toString());
        assertThat(ParquetReadBack.readAll(table())).hasSize(100);

        assertThatThrownBy(() -> roller.roll("quotes", TODAY))
                .isInstanceOf(ArenaInventory.DayAlignmentException.class);
        assertThat(ParquetReadBack.readAll(table())).hasSize(100); // still exactly one D1
    }

    // --- helpers ---

    private ColdConfig config(int segmentsPerFile) {
        return ColdConfig.builder()
                .arenaBaseDir(base)
                .destination(warehouse.toString())
                .sortColumns(Map.of("quotes", List.of("sym", "ts")))
                .segmentsPerFile(segmentsPerFile)
                .livenessProbe(Duration.ofMillis(150))
                .build();
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
        ColdFixtures.MutableClock clock =
                new ColdFixtures.MutableClock(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        try (RotatingWriter writer = RotatingWriter.open(dir, ColdFixtures.quotesSchema(64), 4096, 1L,
                new DailyRotationPolicy(), clock, ColdFixtures.counterNanoClock())) {
            long dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond()
                    * 1_000_000_000L;
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
}
