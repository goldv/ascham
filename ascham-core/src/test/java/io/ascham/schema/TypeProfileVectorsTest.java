package io.ascham.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ascham.layout.PhysicalKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.IntervalUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.DictionaryEncoding;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Drives {@link TypeProfile} through the generated {@code conformance/type_profile_vectors.json}
 * (emitted from {@code spec/format-manifest.toml}). The manifest's {@code [types]} table is the
 * single enumeration of the v1 profile; this test pins the hand-written Java classification to it,
 * and the C++ reader pins its own mapping to the same file — so the profile can no longer drift
 * per language or per copy.
 */
class TypeProfileVectorsTest {

    private static final Path VECTORS =
            Path.of(System.getProperty("io.ascham.conformance.dir", "conformance"))
                    .resolve("type_profile_vectors.json");

    @TestFactory
    List<DynamicTest> typeProfileMatchesVectors() throws Exception {
        JsonNode root = new ObjectMapper().readTree(VECTORS.toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode row : root.get("accepted")) {
            tests.add(DynamicTest.dynamicTest("accepted: " + row.get("id").asText(), () -> {
                Field field = field(row);
                PhysicalKind kind = TypeProfile.classify(field);
                assertThat(kind.name()).isEqualTo(row.get("kind").asText());
                assertThat(kind.wireValue()).isEqualTo(row.get("kind-wire").asInt());
                if (row.has("width")) {
                    assertThat(TypeProfile.fixedWidthBytes(field)).isEqualTo(row.get("width").asInt());
                }
            }));
        }
        for (JsonNode row : root.get("rejected")) {
            tests.add(DynamicTest.dynamicTest("rejected: " + row.get("id").asText(), () ->
                    assertThatThrownBy(() -> TypeProfile.classify(field(row)))
                            .as("type %s must be outside the v1 profile", row.get("id").asText())
                            .isInstanceOf(UnsupportedTypeException.class)));
        }
        return tests;
    }

    /** Builds the Arrow field a vector row describes. The switch is test glue, not contract. */
    private static Field field(JsonNode row) {
        String name = row.get("id").asText();
        if (row.get("arrow").asText().equals("Dictionary")) {
            DictionaryEncoding encoding = new DictionaryEncoding(1, false,
                    (ArrowType.Int) type(row.get("index").asText(), row));
            return new Field(name,
                    new FieldType(true, type(row.get("value").asText(), row), encoding), List.of());
        }
        ArrowType type = type(row.get("arrow").asText(), row);
        List<Field> children = row.has("child")
                ? List.of(new Field("item",
                        FieldType.nullable(type(row.get("child").asText(), row)), List.of()))
                : List.of();
        return new Field(name, FieldType.nullable(type), children);
    }

    private static ArrowType type(String arrow, JsonNode row) {
        return switch (arrow) {
            case "Bool" -> new ArrowType.Bool();
            case "Int8" -> new ArrowType.Int(8, true);
            case "Int16" -> new ArrowType.Int(16, true);
            case "Int32" -> new ArrowType.Int(32, true);
            case "Int64" -> new ArrowType.Int(64, true);
            case "UInt8" -> new ArrowType.Int(8, false);
            case "UInt16" -> new ArrowType.Int(16, false);
            case "UInt32" -> new ArrowType.Int(32, false);
            case "UInt64" -> new ArrowType.Int(64, false);
            case "Float16" -> new ArrowType.FloatingPoint(FloatingPointPrecision.HALF);
            case "Float32" -> new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
            case "Float64" -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case "Decimal128" -> new ArrowType.Decimal(row.get("precision").asInt(), row.get("scale").asInt(), 128);
            case "Decimal256" -> new ArrowType.Decimal(row.get("precision").asInt(), row.get("scale").asInt(), 256);
            case "Date32" -> new ArrowType.Date(DateUnit.DAY);
            case "Date64" -> new ArrowType.Date(DateUnit.MILLISECOND);
            case "Time32" -> new ArrowType.Time(unit(row), 32);
            case "Time64" -> new ArrowType.Time(unit(row), 64);
            case "Timestamp" -> new ArrowType.Timestamp(unit(row),
                    row.has("timezone") ? row.get("timezone").asText() : null);
            case "FixedSizeBinary" -> new ArrowType.FixedSizeBinary(row.get("byte-width").asInt());
            case "Utf8" -> new ArrowType.Utf8();
            case "Binary" -> new ArrowType.Binary();
            case "LargeUtf8" -> new ArrowType.LargeUtf8();
            case "LargeBinary" -> new ArrowType.LargeBinary();
            case "Duration" -> new ArrowType.Duration(unit(row));
            case "Interval" -> new ArrowType.Interval(IntervalUnit.YEAR_MONTH);
            case "Null" -> new ArrowType.Null();
            case "List" -> new ArrowType.List();
            case "Struct" -> new ArrowType.Struct();
            case "Map" -> new ArrowType.Map(false);
            default -> throw new IllegalArgumentException("vector glue does not know type " + arrow);
        };
    }

    private static TimeUnit unit(JsonNode row) {
        return TimeUnit.valueOf(row.get("unit").asText());
    }
}
