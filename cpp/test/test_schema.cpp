// Schema-decode conformance: decodes the embedded Arrow IPC schema of golden segments via nanoarrow
// and checks logical types + ascham.* metadata against the known golden schemas. This is the reader
// core's logical-type layer (the input to the DuckDB arena_scan type mapping).
#include <string>

#include "format/schema.hpp"
#include "format/segment_reader.hpp"
#include "test_framework.hpp"

namespace {

arena::TableSchema golden_schema(const std::string &name) {
    auto reader = arena::SegmentReader::open(::testfw::conformance_dir() + "/golden/" + name + ".bin");
    auto [ptr, len] = reader.embedded_schema();
    return arena::TableSchema::decode(ptr, static_cast<std::size_t>(len));
}

}  // namespace

TEST(all_types_logical_schema) {
    auto s = golden_schema("all_types");
    CHECK_EQ(s.columns.size(), 14u);
    CHECK(s.time_column == "ts");
    CHECK(s.stats_column == "i64");

    using T = arena::LogicalType;
    const T expected[] = {T::TIMESTAMP, T::BOOL, T::INT8, T::UINT16, T::INT32, T::INT64,
                          T::FLOAT32, T::FLOAT64, T::DECIMAL128, T::DATE32, T::TIME64_NS,
                          T::FIXED_SIZE_BINARY, T::UTF8, T::BINARY};
    const char *names[] = {"ts", "flag", "i8", "u16", "i32", "i64", "f32", "f64",
                           "dec", "d32", "t64", "fsb", "sym", "bin"};
    for (int i = 0; i < 14; ++i) {
        CHECK(s.columns[static_cast<std::size_t>(i)].name == names[i]);
        CHECK(s.columns[static_cast<std::size_t>(i)].type == expected[i]);
    }

    const auto &ts = s.columns[0];
    CHECK(ts.timestamp_unit == arena::TimestampUnit::NANO);
    CHECK(ts.timezone == "UTC");
    const auto &dec = s.columns[8];
    CHECK_EQ(dec.decimal_precision, 38);
    CHECK_EQ(dec.decimal_scale, 9);
    CHECK_EQ(s.columns[11].fixed_size, 16);  // fsb
}

TEST(type_bounds_unsigned_and_signed) {
    auto s = golden_schema("type_bounds");
    // ts, i8, i16, i32, i64, u8, u16, u32, u64, f32, f64, dec
    using T = arena::LogicalType;
    CHECK(s.columns[1].type == T::INT8);
    CHECK(s.columns[4].type == T::INT64);
    CHECK(s.columns[5].type == T::UINT8);
    CHECK(s.columns[6].type == T::UINT16);
    CHECK(s.columns[7].type == T::UINT32);
    CHECK(s.columns[8].type == T::UINT64);
    CHECK(s.columns[11].type == T::DECIMAL128);
    CHECK(s.time_column == "ts");
    CHECK(s.stats_column == "i64");
}

TEST(fixed_binary_widths_schema) {
    auto s = golden_schema("fixed_binary_widths");
    // ts, i64, b1, b7, b16, b33
    CHECK_EQ(s.columns[2].fixed_size, 1);
    CHECK_EQ(s.columns[3].fixed_size, 7);
    CHECK_EQ(s.columns[4].fixed_size, 16);
    CHECK_EQ(s.columns[5].fixed_size, 33);
}
