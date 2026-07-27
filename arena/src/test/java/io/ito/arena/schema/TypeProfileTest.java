package io.ito.arena.schema;

import static io.ito.arena.schema.SchemaFixtures.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ito.arena.layout.PhysicalKind;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.IntervalUnit;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.jupiter.api.Test;

class TypeProfileTest {

    @Test
    void acceptsBoolAsBitmap() {
        assertThat(TypeProfile.classify(field("b", new ArrowType.Bool()))).isEqualTo(PhysicalKind.BOOL_BITMAP);
    }

    @Test
    void acceptsFixedWidthTypes() {
        assertThat(TypeProfile.classify(field("i8", new ArrowType.Int(8, true)))).isEqualTo(PhysicalKind.FIXED);
        assertThat(TypeProfile.classify(field("u64", new ArrowType.Int(64, false)))).isEqualTo(PhysicalKind.FIXED);
        assertThat(TypeProfile.classify(field("f32",
                new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)))).isEqualTo(PhysicalKind.FIXED);
        assertThat(TypeProfile.classify(field("dec", new ArrowType.Decimal(38, 9, 128)))).isEqualTo(PhysicalKind.FIXED);
        assertThat(TypeProfile.classify(field("d", new ArrowType.Date(DateUnit.DAY)))).isEqualTo(PhysicalKind.FIXED);
        assertThat(TypeProfile.classify(field("t", new ArrowType.Time(TimeUnit.NANOSECOND, 64)))).isEqualTo(PhysicalKind.FIXED);
        assertThat(TypeProfile.classify(field("ts", new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)))).isEqualTo(PhysicalKind.FIXED);
        assertThat(TypeProfile.classify(field("fsb", new ArrowType.FixedSizeBinary(16)))).isEqualTo(PhysicalKind.FIXED);
    }

    @Test
    void acceptsVarlenTypes() {
        assertThat(TypeProfile.classify(field("s", new ArrowType.Utf8()))).isEqualTo(PhysicalKind.VARLEN);
        assertThat(TypeProfile.classify(field("bin", new ArrowType.Binary()))).isEqualTo(PhysicalKind.VARLEN);
    }

    @Test
    void fixedWidthBytesMatchTypeSize() {
        assertThat(TypeProfile.fixedWidthBytes(field("i16", new ArrowType.Int(16, true)))).isEqualTo(2);
        assertThat(TypeProfile.fixedWidthBytes(field("i32", new ArrowType.Int(32, true)))).isEqualTo(4);
        assertThat(TypeProfile.fixedWidthBytes(field("dec", new ArrowType.Decimal(38, 9, 128)))).isEqualTo(16);
        assertThat(TypeProfile.fixedWidthBytes(field("d", new ArrowType.Date(DateUnit.DAY)))).isEqualTo(4);
        assertThat(TypeProfile.fixedWidthBytes(field("ts", new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")))).isEqualTo(8);
        assertThat(TypeProfile.fixedWidthBytes(field("fsb", new ArrowType.FixedSizeBinary(7)))).isEqualTo(7);
    }

    @Test
    void rejectsDictionaryEncoding() {
        Field dict = SchemaFixtures.dictField("code", new ArrowType.Utf8());
        assertThatThrownBy(() -> TypeProfile.classify(dict))
                .isInstanceOf(UnsupportedTypeException.class)
                .hasMessageContaining("dictionary");
    }

    @Test
    void rejectsNestedAndUnsupportedTypes() {
        assertReject(field("list", new ArrowType.List()));
        assertReject(field("struct", new ArrowType.Struct()));
        assertReject(field("largeutf8", new ArrowType.LargeUtf8()));
        assertReject(field("largebin", new ArrowType.LargeBinary()));
        assertReject(field("nul", new ArrowType.Null()));
        assertReject(field("dur", new ArrowType.Duration(TimeUnit.NANOSECOND)));
        assertReject(field("ival", new ArrowType.Interval(IntervalUnit.DAY_TIME)));
    }

    @Test
    void rejectsOutOfProfileVariants() {
        assertReject(field("f16", new ArrowType.FloatingPoint(FloatingPointPrecision.HALF)));
        assertReject(field("dec256", new ArrowType.Decimal(60, 9, 256)));
        assertReject(field("date64", new ArrowType.Date(DateUnit.MILLISECOND)));
        assertReject(field("time32", new ArrowType.Time(TimeUnit.MILLISECOND, 32)));
        assertReject(field("ts_ms", new ArrowType.Timestamp(TimeUnit.MILLISECOND, "UTC")));
    }

    private static void assertReject(Field f) {
        assertThatThrownBy(() -> TypeProfile.classify(f)).isInstanceOf(UnsupportedTypeException.class);
    }
}
