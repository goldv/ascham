package io.ascham.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetReaders;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.parquet.Parquet;

/**
 * Test-side table read-back through the parquet reader directly. IcebergGenerics would be the
 * obvious tool, but its {@code FormatModelRegistry} initialisation hard-requires the ORC writer on
 * the classpath (iceberg 1.11.0) and the roll is deliberately parquet-only — reading files
 * ourselves keeps that dependency out. Also gives per-file reads, which the sortedness assertions
 * want anyway.
 */
final class ParquetReadBack {

    private ParquetReadBack() {
    }

    /** Every row of every data file in the table's current snapshot, in file order. */
    static List<Record> readAll(Table table) {
        List<Record> out = new ArrayList<>();
        try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
            for (FileScanTask task : tasks) {
                out.addAll(readFile(table, task.file().location()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** All rows of one data file, in row order (the order the roll wrote them). */
    static List<Record> readFile(Table table, String location) {
        List<Record> out = new ArrayList<>();
        try (CloseableIterable<Record> rows = Parquet.read(table.io().newInputFile(location))
                .project(table.schema())
                .createReaderFunc(type -> GenericParquetReaders.buildReader(table.schema(), type))
                .build()) {
            rows.forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
