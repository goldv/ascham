// Reader-core conformance tests against the checked-in golden corpus (conformance/golden/*.bin).
// Expected values are the deterministic golden data defined in the Java GoldenCases; this is the
// C++ side of the cross-language format contract. No DuckDB, no external test framework. Also hosts
// the test runner (main) for all reader-core test files.
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "format/arena_format.hpp"
#include "format/segment_reader.hpp"
#include "format/table_dir.hpp"
#include "test_framework.hpp"

namespace {

constexpr std::int64_t BASE_TS = 1'700'000'000'000'000'000LL;

arena::SegmentReader golden(const std::string &case_name) {
    return arena::SegmentReader::open(::testfw::conformance_dir() + "/golden/" + case_name + ".bin");
}

// pattern(n, seed) mirrors the Java GoldenCases helper: b[i] = (byte)(seed*n + i).
std::vector<std::uint8_t> pattern(int n, int seed) {
    std::vector<std::uint8_t> b(static_cast<std::size_t>(n));
    for (int i = 0; i < n; ++i) b[static_cast<std::size_t>(i)] = static_cast<std::uint8_t>(seed * n + i);
    return b;
}

std::vector<std::uint8_t> read_all(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

// column ordinals in the all-types schema
enum { TS = 0, FLAG = 1, I8 = 2, U16 = 3, I32 = 4, I64 = 5, F32 = 6, F64 = 7,
       DEC = 8, D32 = 9, T64 = 10, FSB = 11, SYM = 12, BIN = 13 };

}  // namespace

TEST(header_hash_and_layout) {
    auto r = golden("all_types");
    CHECK_EQ(r.header().batch_rows, 4);
    CHECK_EQ(r.header().arena_capacity, 16384);
    CHECK_EQ(r.header().batch_stride, 4096);
    CHECK_EQ(r.column_count(), 14);

    const auto &cols = r.layout().columns;
    const char *names[] = {"ts","flag","i8","u16","i32","i64","f32","f64","dec","d32","t64","fsb","sym","bin"};
    for (int i = 0; i < 14; ++i) {
        CHECK(cols[static_cast<std::size_t>(i)].name == names[i]);
        CHECK_EQ(cols[static_cast<std::size_t>(i)].validity_offset % 64, 0);
        CHECK_EQ(cols[static_cast<std::size_t>(i)].data_offset % 64, 0);
    }
    CHECK(cols[FLAG].kind == arena::PhysicalKind::BOOL_BITMAP);
    CHECK(cols[SYM].kind == arena::PhysicalKind::VARLEN);
    CHECK(cols[I64].kind == arena::PhysicalKind::FIXED);
    CHECK_EQ(cols[I64].element_width, 8);
    CHECK_EQ(cols[FSB].element_width, 16);
}

TEST(all_types_batches_and_stats) {
    auto r = golden("all_types");
    CHECK_EQ(r.header().active_batch_count, 2);
    CHECK_EQ(r.batches().size(), 2u);
    CHECK_EQ(r.batches()[0].row_count, 4);
    CHECK(r.batches()[0].sealed);
    CHECK_EQ(r.batches()[1].row_count, 2);
    CHECK(!r.batches()[1].sealed);
    CHECK_EQ(r.batches()[0].base_offset, r.header().data_offset);
    CHECK_EQ(r.batches()[1].base_offset, r.header().data_offset + r.header().batch_stride);
    CHECK_EQ(r.batches()[0].ts_min, BASE_TS);
    CHECK_EQ(r.batches()[0].ts_max, BASE_TS + 3);
    CHECK_EQ(r.batches()[0].stat_min, 0);
    CHECK_EQ(r.batches()[0].stat_max, 3'000'000);
}

TEST(all_types_values) {
    auto r = golden("all_types");
    auto check_row = [&](int batch, int row, int r_global) {
        CHECK_EQ(r.fixed<std::int64_t>(batch, row, TS), BASE_TS + r_global);
        CHECK_EQ(r.boolean(batch, row, FLAG), (r_global & 1) == 0);
        CHECK_EQ(r.fixed<std::int8_t>(batch, row, I8), static_cast<std::int8_t>(r_global));
        CHECK_EQ(r.fixed<std::int16_t>(batch, row, U16), static_cast<std::int16_t>(r_global * 7));
        CHECK_EQ(r.fixed<std::int32_t>(batch, row, I32), r_global * 1000);
        CHECK_EQ(r.fixed<std::int64_t>(batch, row, I64), static_cast<std::int64_t>(r_global) * 1'000'000);
        CHECK(r.fixed<float>(batch, row, F32) == static_cast<float>(r_global) + 0.5f);
        CHECK(r.fixed<double>(batch, row, F64) == static_cast<double>(r_global) + 0.25);
        const std::uint8_t* dec = r.fixed_ptr(batch, row, DEC);
        CHECK_EQ(arena::load_le<std::int64_t>(dec, 0), static_cast<std::int64_t>(r_global) * 123456789LL);
        CHECK_EQ(arena::load_le<std::int64_t>(dec, 8), 0);
        CHECK_EQ(r.fixed<std::int32_t>(batch, row, D32), 19000 + r_global);
        CHECK_EQ(r.fixed<std::int64_t>(batch, row, T64), static_cast<std::int64_t>(r_global) * 1000);
        auto expect_fsb = pattern(16, r_global);
        CHECK(std::memcmp(r.fixed_ptr(batch, row, FSB), expect_fsb.data(), 16) == 0);
        auto [sym_ptr, sym_len] = r.varlen(batch, row, SYM);
        std::string sym(reinterpret_cast<const char*>(sym_ptr), static_cast<std::size_t>(sym_len));
        CHECK(sym == ("s" + std::to_string(r_global)));
        auto [bin_ptr, bin_len] = r.varlen(batch, row, BIN);
        CHECK_EQ(bin_len, 2);
        CHECK_EQ(bin_ptr[0], static_cast<std::uint8_t>(r_global));
        CHECK_EQ(bin_ptr[1], static_cast<std::uint8_t>(r_global + 1));
        CHECK(r.is_valid(batch, row, TS));
        CHECK(r.is_valid(batch, row, SYM));
    };
    for (int row = 0; row < 4; ++row) check_row(0, row, row);
    for (int row = 0; row < 2; ++row) check_row(1, row, 4 + row);
}

TEST(all_null_validity) {
    auto r = golden("all_null");
    CHECK_EQ(r.header().active_batch_count, 1);
    CHECK_EQ(r.batches()[0].row_count, 3);
    CHECK(!r.batches()[0].sealed);
    for (int row = 0; row < 3; ++row) {
        CHECK(r.is_valid(0, row, TS));
        CHECK(r.is_valid(0, row, I64));
        CHECK(!r.is_valid(0, row, FLAG));
        CHECK(!r.is_valid(0, row, I32));
        CHECK(!r.is_valid(0, row, SYM));
        CHECK_EQ(r.fixed<std::int64_t>(0, row, TS), BASE_TS + row);
        CHECK_EQ(r.fixed<std::int64_t>(0, row, I64), row);
    }
}

TEST(empty_batch) {
    auto r = golden("empty_batch");
    CHECK_EQ(r.header().active_batch_count, 2);
    CHECK_EQ(r.batches()[0].row_count, 0);
    CHECK(r.batches()[0].sealed);
    CHECK_EQ(r.batches()[1].row_count, 0);
    CHECK(!r.batches()[1].sealed);
}

TEST(in_progress_mid_append) {
    auto r = golden("in_progress");
    CHECK_EQ(r.header().active_batch_count, 1);
    CHECK_EQ(r.batches()[0].row_count, 5);
    CHECK(!r.batches()[0].sealed);
}

TEST(varlen_empty_strings) {
    auto r = golden("varlen_empty");
    CHECK_EQ(r.batches()[0].row_count, 3);
    CHECK(r.batches()[0].sealed);
    for (int row = 0; row < 3; ++row) {
        auto [ptr, len] = r.varlen(0, row, 2);
        (void) ptr;
        CHECK_EQ(len, 0);
        CHECK(r.is_valid(0, row, 2));
        CHECK_EQ(r.fixed<std::int64_t>(0, row, 0), BASE_TS + row);
    }
}

TEST(varlen_exact_capacity_and_migration) {
    auto r = golden("varlen_exact_capacity");
    CHECK_EQ(r.header().active_batch_count, 2);
    CHECK_EQ(r.batches()[0].row_count, 1);
    CHECK(r.batches()[0].sealed);
    CHECK_EQ(r.batches()[1].row_count, 1);
    CHECK(!r.batches()[1].sealed);

    auto [a_ptr, a_len] = r.varlen(0, 0, 2);
    CHECK_EQ(a_len, 16);
    for (int i = 0; i < 16; ++i) CHECK_EQ(a_ptr[i], static_cast<std::uint8_t>('A'));
    CHECK_EQ(r.fixed<std::int64_t>(0, 0, 1), 10);

    auto [b_ptr, b_len] = r.varlen(1, 0, 2);
    CHECK_EQ(b_len, 1);
    CHECK_EQ(b_ptr[0], static_cast<std::uint8_t>('B'));
    CHECK_EQ(r.fixed<std::int64_t>(1, 0, 0), BASE_TS + 1);
    CHECK_EQ(r.fixed<std::int64_t>(1, 0, 1), 20);
}

TEST(fixed_binary_widths) {
    auto r = golden("fixed_binary_widths");
    CHECK_EQ(r.batches()[0].row_count, 4);
    CHECK_EQ(r.column(2).element_width, 1);
    CHECK_EQ(r.column(3).element_width, 7);
    CHECK_EQ(r.column(4).element_width, 16);
    CHECK_EQ(r.column(5).element_width, 33);
    for (int row = 0; row < 4; ++row) {
        for (int col = 2; col <= 5; ++col) {
            int width = (col == 2) ? 1 : (col == 3) ? 7 : (col == 4) ? 16 : 33;
            auto expect = pattern(width, row);
            CHECK(std::memcmp(r.fixed_ptr(0, row, col), expect.data(), static_cast<std::size_t>(width)) == 0);
        }
    }
}

TEST(type_bounds) {
    auto r = golden("type_bounds");
    enum { B_I8 = 1, B_I16 = 2, B_I32 = 3, B_I64 = 4, B_U8 = 5, B_U16 = 6, B_U32 = 7, B_U64 = 8,
           B_F32 = 9, B_F64 = 10, B_DEC = 11 };
    CHECK_EQ(r.batches()[0].row_count, 3);

    CHECK_EQ(r.fixed<std::int8_t>(0, 0, B_I8), INT8_MIN);
    CHECK_EQ(r.fixed<std::int16_t>(0, 0, B_I16), INT16_MIN);
    CHECK_EQ(r.fixed<std::int32_t>(0, 0, B_I32), INT32_MIN);
    CHECK_EQ(r.fixed<std::int64_t>(0, 0, B_I64), INT64_MIN);
    CHECK(r.fixed<float>(0, 0, B_F32) == -__FLT_MAX__);
    CHECK(r.fixed<double>(0, 0, B_F64) == -__DBL_MAX__);
    CHECK_EQ(arena::load_le<std::int64_t>(r.fixed_ptr(0, 0, B_DEC), 0), 0);
    CHECK_EQ(arena::load_le<std::int64_t>(r.fixed_ptr(0, 0, B_DEC), 8), INT64_MIN);

    CHECK_EQ(r.fixed<std::int8_t>(0, 1, B_I8), INT8_MAX);
    CHECK_EQ(r.fixed<std::int64_t>(0, 1, B_I64), INT64_MAX);
    CHECK_EQ(r.fixed<std::uint8_t>(0, 1, B_U8), 0xFFu);
    CHECK_EQ(r.fixed<std::uint16_t>(0, 1, B_U16), 0xFFFFu);
    CHECK_EQ(r.fixed<std::int32_t>(0, 1, B_U32), -1);
    CHECK_EQ(r.fixed<std::int64_t>(0, 1, B_U64), -1);
    CHECK_EQ(arena::load_le<std::int64_t>(r.fixed_ptr(0, 1, B_DEC), 0), -1);
    CHECK_EQ(arena::load_le<std::int64_t>(r.fixed_ptr(0, 1, B_DEC), 8), INT64_MAX);

    CHECK_EQ(r.fixed<std::int64_t>(0, 2, B_I64), 0);
}

TEST(corrupt_schema_and_magic_are_hard_failures) {
    namespace fs = std::filesystem;
    fs::path tmp = fs::temp_directory_path() / "arena_corrupt_test";
    fs::create_directories(tmp);

    auto bytes = read_all(::testfw::conformance_dir() + "/golden/all_types.bin");
    CHECK(!bytes.empty());

    auto write = [&](const std::string& name, std::vector<std::uint8_t> b) {
        std::string p = (tmp / name).string();
        std::ofstream out(p, std::ios::binary);
        out.write(reinterpret_cast<const char*>(b.data()), static_cast<std::streamsize>(b.size()));
        out.close();
        return p;
    };

    auto schema_corrupt = bytes;
    schema_corrupt[4096] ^= 0xFF;
    CHECK_THROWS(arena::SegmentReader::open(write("schema.bin", schema_corrupt)));

    auto magic_corrupt = bytes;
    magic_corrupt[0] ^= 0xFF;
    CHECK_THROWS(arena::SegmentReader::open(write("magic.bin", magic_corrupt)));

    fs::remove_all(tmp);
}

TEST(table_dir_lists_segments_oldest_first) {
    namespace fs = std::filesystem;
    fs::path dir = fs::temp_directory_path() / "arena_table_dir_test";
    fs::remove_all(dir);
    fs::create_directories(dir);
    for (const char* name : {"20260729.0.arena", "20260728.1.arena", "20260728.0.arena",
                             "20260728.0.arena.tmp.1.1", "notasegment.txt"}) {
        std::ofstream(dir / name).put('x');
    }
    auto segs = arena::list_segments(dir.string());
    CHECK_EQ(segs.size(), 3u);
    CHECK_EQ(segs[0].year_month_day, 20260728);
    CHECK_EQ(segs[0].sequence, 0);
    CHECK_EQ(segs[1].sequence, 1);
    CHECK_EQ(segs[2].year_month_day, 20260729);
    fs::remove_all(dir);
}

int main(int argc, char** argv) {
    if (argc > 1) ::testfw::conformance_dir() = argv[1];
    int passed = 0, failed = 0;
    for (const auto& t : ::testfw::registry()) {
        try {
            t.fn();
            std::printf("  [PASS] %s\n", t.name.c_str());
            ++passed;
        } catch (const ::testfw::Failure& f) {
            std::printf("  [FAIL] %s\n         %s\n", t.name.c_str(), f.message.c_str());
            ++failed;
        } catch (const std::exception& e) {
            std::printf("  [FAIL] %s\n         unexpected exception: %s\n", t.name.c_str(), e.what());
            ++failed;
        }
    }
    std::printf("\n%d passed, %d failed\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
