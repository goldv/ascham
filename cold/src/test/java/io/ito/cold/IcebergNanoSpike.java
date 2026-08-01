package io.ito.cold;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.DateTimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The risk-retirement spike for the native roll (task 3 of the migration): proves that
 * iceberg-java 1.11 can create a format-version-3 table with a timestamptz_ns column partitioned
 * by day(ts) on a local HadoopCatalog, write it through the generic parquet path with a directly
 * built partition struct, and read the nanoseconds back intact. If this fails, the executor design
 * changes (internal writer model) — so it runs before anything is built on top.
 *
 * <p>Absorbed by LocalRollTest once the executor exists; kept because it pins the raw API path.
 */
class IcebergNanoSpike {

    @TempDir
    Path warehouse;

    @Test
    void nanosecondsSurviveV3WriteAndReadBackOnLocalDisk() throws Exception {
        Schema schema = new Schema(
                Types.NestedField.optional(1, "ts", Types.TimestampNanoType.withoutZone()),
                Types.NestedField.optional(2, "sym", Types.StringType.get()),
                Types.NestedField.optional(3, "px", Types.LongType.get()));
        PartitionSpec spec = PartitionSpec.builderFor(schema).day("ts").build();

        long baseNanos = java.time.OffsetDateTime.parse("2026-07-30T09:30:00Z")
                .toEpochSecond() * 1_000_000_000L + 1; // ...ends in 001: sub-micro digit must survive
        int epochDay = (int) java.time.LocalDate.parse("2026-07-30").toEpochDay();

        try (HadoopCatalog catalog = new HadoopCatalog(new Configuration(), warehouse.toString())) {
            Table table = catalog.createTable(
                    TableIdentifier.of("ito", "quotes"), schema, spec,
                    Map.of("format-version", "3",
                            "write.parquet.compression-codec", "zstd"));

            GenericRecord partition = GenericRecord.create(spec.partitionType());
            partition.set(0, epochDay);

            OutputFileFactory files = OutputFileFactory.builderFor(table, 1, 1)
                    .format(FileFormat.PARQUET).build();

            DataFile dataFile;
            // Parquet.writeData, not GenericAppenderFactory: the factory's format switch drags the
            // ORC writer onto the classpath; the roll is parquet-only by design.
            try (DataWriter<Record> writer = Parquet.writeData(files.newOutputFile(spec, partition))
                    .schema(schema)
                    .setAll(table.properties())
                    .metricsConfig(MetricsConfig.forTable(table))
                    .createWriterFunc(GenericParquetWriter::create)
                    .withSpec(spec)
                    .withPartition(partition)
                    .build()) {
                for (int i = 0; i < 3; i++) {
                    GenericRecord r = GenericRecord.create(schema);
                    r.set(0, DateTimeUtil.timestampFromNanos(baseNanos + i));
                    r.set(1, "AAPL");
                    r.set(2, 100L + i);
                    writer.write(r);
                }
                writer.close();
                dataFile = writer.toDataFile();
            }
            table.newAppend().appendFile(dataFile).set("ito.day", "2026-07-30").commit();

            List<Long> nanos = ParquetReadBack.readAll(table).stream()
                    .map(r -> DateTimeUtil.nanosFromTimestamp((java.time.LocalDateTime) r.getField("ts")))
                    .sorted().toList();
            assertThat(nanos).containsExactly(baseNanos, baseNanos + 1, baseNanos + 2);

            assertThat(table.currentSnapshot().summary()).containsEntry("ito.day", "2026-07-30");
            assertThat(dataFile.partition().get(0, Integer.class)).isEqualTo(epochDay);
        }
    }

    /** StructLike sanity: what the executor will hand to OutputFileFactory/DataWriter. */
    @Test
    void directPartitionStructMatchesTransformOutput() {
        Schema schema = new Schema(Types.NestedField.optional(1, "ts", Types.TimestampNanoType.withoutZone()));
        PartitionSpec spec = PartitionSpec.builderFor(schema).day("ts").build();
        StructLike partition = GenericRecord.create(spec.partitionType());
        ((GenericRecord) partition).set(0, 20664); // 2026-07-30
        assertThat(spec.partitionToPath(partition)).isEqualTo("ts_day=2026-07-30");
    }
}
