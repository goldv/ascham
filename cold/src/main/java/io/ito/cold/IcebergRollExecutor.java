package io.ito.cold;

import io.ito.arena.schema.ArenaSchema;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.parquet.Parquet;

/**
 * Rolls days into Iceberg natively: arena segments are read through their zero-copy Arrow roots,
 * each file group is ordered by the index sort, rows stream through the generic parquet writer,
 * and one transaction commits the whole day — data files, segment provenance in the snapshot
 * summary, and the watermark table property, atomically.
 *
 * <p>State lives in the table itself, in two places with two jobs:
 * <ul>
 *   <li><b>Table properties</b> carry correctness: {@code ito.arena-dir} (which arena owns this
 *       table, checked on every open) and {@code ito.rolled-through} (the day watermark). They are
 *       updated in the same commit as the data and survive snapshot expiration.</li>
 *   <li><b>Snapshot summaries</b> carry provenance: which segment files fed each commit
 *       ({@code ito.day}, {@code ito.segments}, {@code ito.arena-dir}, {@code ito.rows}) — the
 *       input a reclaim utility needs, consumed before snapshots are expired.</li>
 * </ul>
 *
 * <p>Not thread-safe: one instance drives one roll pass, and {@link TableRoller} is
 * single-threaded per table by design.
 */
public final class IcebergRollExecutor implements RollExecutor {

    private static final System.Logger LOG = System.getLogger(IcebergRollExecutor.class.getName());

    /** Table property: the arena table directory this table archives. Ownership, not audit. */
    public static final String PROP_ARENA_DIR = "ito.arena-dir";
    /** Table property: the highest fully-committed day (ISO date). The watermark. */
    public static final String PROP_ROLLED_THROUGH = "ito.rolled-through";
    /** Snapshot summary: the day this commit archived (ISO date). */
    public static final String SUMMARY_DAY = "ito.day";
    /** Snapshot summary: comma-joined segment file names this commit was built from. */
    public static final String SUMMARY_SEGMENTS = "ito.segments";
    /** Snapshot summary: the arena table directory the segments were read from. */
    public static final String SUMMARY_ARENA_DIR = "ito.arena-dir";
    /** Snapshot summary: rows written by this commit. */
    public static final String SUMMARY_ROWS = "ito.rows";

    private final ColdConfig config;
    private final Catalog catalog;
    private final Map<String, Table> tables = new HashMap<>();

    public IcebergRollExecutor(ColdConfig config) {
        this.config = config;
        this.catalog = CatalogFactory.open(config);
    }

    @Override
    public void ensureTable(String table, ArenaSchema schema, List<String> sortColumns) {
        TableIdentifier id = TableIdentifier.of(config.namespace(), table);
        ensureNamespace();

        Table loaded;
        try {
            loaded = catalog.loadTable(id);
        } catch (NoSuchTableException absent) {
            loaded = createTable(id, schema, sortColumns);
        }
        verifyOwnership(table, loaded);
        tables.put(table, loaded);
    }

    @Override
    public Optional<LocalDate> highestRolledDay(String table) {
        Table t = tables.get(table);
        if (t == null) {
            try {
                t = catalog.loadTable(TableIdentifier.of(config.namespace(), table));
                tables.put(table, t);
            } catch (NoSuchTableException absent) {
                return Optional.empty();
            }
        } else {
            t.refresh();
        }
        return Optional.ofNullable(t.properties().get(PROP_ROLLED_THROUGH)).map(LocalDate::parse);
    }

    @Override
    public long rollDay(String table, ArenaSchema schema, LocalDate day, List<Path> segments,
                        List<String> sortColumns) {
        Table t = tables.get(table);
        if (t == null) {
            throw new IllegalStateException("ensureTable must run before rollDay for " + table);
        }

        List<DataFile> files = new ArrayList<>();
        long rows = 0;
        try {
            for (List<Path> groupSegments : groups(segments, config.segmentsPerFile())) {
                try (SegmentGroup group = SegmentGroup.open(groupSegments)) {
                    files.add(writeGroup(t, schema, day, group, sortColumns));
                    rows += group.rowCount();
                }
            }

            Transaction txn = t.newTransaction();
            AppendFiles append = txn.newAppend();
            files.forEach(append::appendFile);
            append.set(SUMMARY_DAY, day.toString());
            append.set(SUMMARY_SEGMENTS,
                    String.join(",", segments.stream().map(p -> p.getFileName().toString()).toList()));
            append.set(SUMMARY_ARENA_DIR, config.arenaDirOf(table));
            append.set(SUMMARY_ROWS, Long.toString(rows));
            append.commit();
            txn.updateProperties().set(PROP_ROLLED_THROUGH, day.toString()).commit();
            txn.commitTransaction();
            return rows;
        } catch (RuntimeException e) {
            // Nothing committed. Best-effort removal of the files already written; anything left
            // behind is an unreferenced orphan for standard orphan-file cleanup to collect.
            deleteQuietly(t, files);
            throw e instanceof ColdException c ? c
                    : new ColdException("failed to roll " + table + " day " + day, e);
        }
    }

    @Override
    public void close() {
        if (catalog instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "failed to close the catalog", e);
            }
        }
    }

    // --- table lifecycle ---

    private void ensureNamespace() {
        if (catalog instanceof SupportsNamespaces namespaces) {
            Namespace ns = Namespace.of(config.namespace());
            try {
                if (!namespaces.namespaceExists(ns)) {
                    namespaces.createNamespace(ns);
                }
            } catch (AlreadyExistsException raced) {
                // Another roller created it between the check and the create: fine.
            }
        }
    }

    private Table createTable(TableIdentifier id, ArenaSchema schema, List<String> sortColumns) {
        Schema icebergSchema = IcebergTypes.icebergSchema(schema);
        String timeColumn = schema.metadata().timeColumn();
        PartitionSpec spec = PartitionSpec.builderFor(icebergSchema).day(timeColumn).build();
        SortOrder.Builder order = SortOrder.builderFor(icebergSchema);
        sortColumns.forEach(order::asc);

        try {
            return catalog.buildTable(id, icebergSchema)
                    .withPartitionSpec(spec)
                    // Declared, not just produced — an upgrade over the DuckDB roller, which could
                    // only sort physically (SET SORTED BY was unimplemented there).
                    .withSortOrder(order.build())
                    .withProperties(Map.of(
                            "format-version", "3", // v3: TIMESTAMP_NS needs it
                            "write.parquet.compression-codec", "zstd",
                            "write.target-file-size-bytes", Long.toString(config.targetFileSizeBytes()),
                            PROP_ARENA_DIR, config.arenaDirOf(id.name())))
                    .create();
        } catch (AlreadyExistsException raced) {
            return catalog.loadTable(id);
        }
    }

    private void verifyOwnership(String table, Table loaded) {
        String owner = loaded.properties().get(PROP_ARENA_DIR);
        String mine = config.arenaDirOf(table);
        if (!mine.equals(owner)) {
            // Skipping would silently strand this arena's rows — never archived, never reclaimed,
            // memory growing with nothing but "rolled 0 days" to show for it. Rolling anyway would
            // duplicate days in history. Neither is acceptable: stop and make the operator resolve
            // the collision.
            throw new ColdException("historical table " + table + " belongs to a different arena ("
                    + owner + ", not " + mine + "). Two arenas must not share one catalog table: "
                    + "point this roller at a different namespace or destination.");
        }
    }

    // --- the write path ---

    private DataFile writeGroup(Table t, ArenaSchema schema, LocalDate day, SegmentGroup group,
                                List<String> sortColumns) {
        int[] order = GroupSorter.sortedIndex(group, sortColumns);
        IcebergTypes.ColumnCopier[] copiers = IcebergTypes.copiers(schema);
        int timeOrdinal = ordinalOf(schema, schema.metadata().timeColumn());
        long dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond() * 1_000_000_000L;
        long nextDay = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond()
                * 1_000_000_000L;

        // The partition tuple is built directly — one int, days from epoch — rather than derived
        // from row values, because the day is already decided and verified (I2).
        GenericRecord partition = GenericRecord.create(t.spec().partitionType());
        partition.set(0, (int) day.toEpochDay());

        OutputFileFactory files = OutputFileFactory.builderFor(t, 0, 0)
                .format(FileFormat.PARQUET).build();
        Schema tableSchema = t.schema();

        try (DataWriter<Record> writer = Parquet.writeData(files.newOutputFile(t.spec(), partition))
                .schema(tableSchema)
                .setAll(t.properties())
                .metricsConfig(MetricsConfig.forTable(t))
                .createWriterFunc(GenericParquetWriter::create)
                .withSpec(t.spec())
                .withPartition(partition)
                .build()) {
            for (int g : order) {
                int b = group.batchOf(g);
                VectorSchemaRoot root = group.root(b);
                int row = g - group.batchStart(b);

                // Belt-and-braces: day-alignment is verified up front from the zone maps, so this
                // should never fire. It is here so that even a verification bug cannot smear rows
                // across partitions or desynchronise the watermark from the data (§3.2, I2).
                long ts = ((TimeStampVector) root.getVector(timeOrdinal)).get(row);
                if (ts < dayStart || ts >= nextDay) {
                    throw new ColdException("row timestamp " + ts + " escapes day " + day
                            + " [" + dayStart + ", " + nextDay + ") — day-alignment verification "
                            + "should have caught this");
                }

                GenericRecord record = GenericRecord.create(tableSchema);
                for (int c = 0; c < copiers.length; c++) {
                    record.set(c, copiers[c].read(root, row));
                }
                writer.write(record);
            }
            writer.close();
            return writer.toDataFile();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write a parquet group for day " + day, e);
        }
    }

    private static int ordinalOf(ArenaSchema schema, String column) {
        for (int i = 0; i < schema.columnCount(); i++) {
            if (schema.field(i).getName().equals(column)) {
                return i;
            }
        }
        throw new ColdException("time column '" + column + "' does not exist in the arena schema");
    }

    /** Consecutive runs of {@code size} — the file-size dial (N segments in, one parquet out). */
    private static List<List<Path>> groups(List<Path> segments, int size) {
        List<List<Path>> out = new ArrayList<>();
        for (int i = 0; i < segments.size(); i += size) {
            out.add(segments.subList(i, Math.min(i + size, segments.size())));
        }
        return out;
    }

    private static void deleteQuietly(Table t, List<DataFile> files) {
        for (DataFile file : files) {
            try {
                t.io().deleteFile(file.location());
            } catch (RuntimeException cleanup) {
                LOG.log(System.Logger.Level.WARNING,
                        "could not remove uncommitted file {0}; orphan-file cleanup will collect it",
                        file.location());
            }
        }
    }
}
