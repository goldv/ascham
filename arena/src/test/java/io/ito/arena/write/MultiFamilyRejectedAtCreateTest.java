package io.ito.arena.write;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.MetadataKeys;
import java.nio.file.Path;
import java.util.List;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiFamilyRejectedAtCreateTest {

    @TempDir
    Path dir;

    @Test
    void multiFamilySchemaIsRejectedAtCreateNotLoad() {
        // Valid schema (loads fine), but declares two families: v1 writer supports only "base".
        Schema arrow = new Schema(List.of(
                WriterFixtures.field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                WriterFixtures.field("a", new ArrowType.Int(64, true)),
                WriterFixtures.field("b", new ArrowType.Int(64, true),
                        WriterFixtures.meta(MetadataKeys.FAMILY, "aux"))),
                WriterFixtures.meta(
                        MetadataKeys.TABLE, "t",
                        MetadataKeys.SCHEMA_VERSION, "1",
                        MetadataKeys.TIME_COLUMN, "ts"));
        ArenaSchema schema = ArenaSchema.load(arrow); // must NOT throw

        assertThatThrownBy(() -> SegmentWriter.createSegment(
                dir.resolve("seg.arena"), schema, 8, 1L, 1L, new WriterFixtures.FakeClock(0, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-family");
    }
}
