package io.ito.arena.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.ito.arena.schema.ArenaSchema;
import io.ito.arena.schema.CanonicalSchema;
import io.ito.arena.schema.MetadataKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateAppendersMainTest {

    @TempDir
    Path dir;

    @Test
    void generatesAppenderSourceFromArrowsFile() throws Exception {
        ArenaSchema schema = ArenaSchema.load(new Schema(
                List.of(field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")),
                        field("px", new ArrowType.Int(64, true))),
                Map.of(MetadataKeys.TABLE, "quotes",
                        MetadataKeys.SCHEMA_VERSION, "1",
                        MetadataKeys.TIME_COLUMN, "ts")));

        Path schemaFile = dir.resolve("quotes.arrows");
        Files.write(schemaFile, CanonicalSchema.canonicalBytes(schema));
        Path outDir = dir.resolve("gen");

        Path written = GenerateAppendersMain.generateOne(schemaFile, outDir, "io.ito.arena.gen");

        assertThat(written).isEqualTo(outDir.resolve("io/ito/arena/gen/QuotesAppender.java"));
        assertThat(Files.readString(written))
                .contains("public final class QuotesAppender extends RowAppender")
                .contains("public void setTs(long value)")
                .contains("public void setPx(long value)");
    }

    @Test
    void classNameForCapitalisesTable() {
        assertThat(GenerateAppendersMain.classNameFor("corp_bonds")).isEqualTo("CorpBondsAppender");
    }

    private static Field field(String name, ArrowType type) {
        return new Field(name, new FieldType(true, type, null, Map.of()), List.of());
    }
}
