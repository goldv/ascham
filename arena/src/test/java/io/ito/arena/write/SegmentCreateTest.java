package io.ito.arena.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.layout.LayoutCodec;
import io.ito.arena.layout.LayoutDescriptor;
import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.CanonicalSchema;
import io.ito.arena.segment.Regions;
import io.ito.arena.segment.SegmentFile;
import io.ito.arena.segment.SegmentHeader;
import io.ito.arena.util.Alignment;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentCreateTest {

    @TempDir
    Path dir;

    @Test
    void createWritesHeaderSchemaDescriptorAndOpensBatchZero() {
        ArenaSchema schema = WriterFixtures.allTypesSchema(8);
        Path path = dir.resolve("seg.arena");

        try (SegmentWriter writer = SegmentWriter.createSegment(
                path, schema, 16, 7L, 3L, new WriterFixtures.FakeClock(0, 1))) {

            assertThat(Files.exists(path)).isTrue();
            assertThat(dir.toFile().list()).containsExactly("seg.arena"); // temp file renamed away

            SegmentFile file = writer.segmentFile();
            Regions regions = writer.regions();

            // Region placement: 64-aligned control regions, page-aligned data.
            assertThat(regions.schemaOffset()).isEqualTo(4096);
            assertThat(regions.layoutOffset() % Alignment.BUFFER_ALIGN).isZero();
            assertThat(regions.catalogOffset() % Alignment.BUFFER_ALIGN).isZero();
            assertThat(regions.dataOffset() % Alignment.PAGE_ALIGN).isZero();
            assertThat(regions.catalogCapacity()).isEqualTo(16);

            SegmentHeader header = new SegmentHeader(file.control());
            header.verifyMagicAndVersion();
            assertThat(header.schemaSha256()).isEqualTo(CanonicalSchema.sha256(schema));
            assertThat(header.writerEpoch()).isEqualTo(7L);
            assertThat(header.segmentSequence()).isEqualTo(3L);
            assertThat(header.batchRows()).isEqualTo(8L);
            assertThat(header.arenaCapacity()).isEqualTo(regions.dataOffset() + regions.dataLength());

            // Embedded schema bytes are exactly the canonical bytes.
            byte[] embedded = new byte[(int) regions.schemaLength()];
            file.control().getBytes((int) regions.schemaOffset(), embedded);
            assertThat(embedded).isEqualTo(CanonicalSchema.canonicalBytes(schema));

            // Embedded descriptor decodes back to the computed layout.
            LayoutDescriptor decoded = LayoutCodec.decode(
                    file.data(), (int) regions.layoutOffset(), (int) regions.layoutLength());
            assertThat(decoded).isEqualTo(writer.layoutDescriptor());

            // Batch 0 is open, in progress, zero rows.
            RawSegmentReader reader = new RawSegmentReader(file, writer.layoutDescriptor(), regions);
            assertThat(reader.activeBatchCount()).isEqualTo(1);
            assertThat(reader.rowCount(0)).isZero();
            assertThat(reader.inProgress(0)).isTrue();
        }
    }

    @Test
    void rejectsCapacityAboveTwoGigabytes() {
        ArenaSchema schema = WriterFixtures.allTypesSchema(1_000_000);
        Path path = dir.resolve("big.arena");
        // 1M-row batches × many batches blows past Integer.MAX_VALUE.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        SegmentWriter.createSegment(path, schema, 4096, 1L, 1L, new WriterFixtures.FakeClock(0, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 GB");
    }
}
