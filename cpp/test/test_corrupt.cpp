// Regression tests for the bounds validation in SegmentReader::open.
//
// Everything the reader loads past the magic and version comes from the file and is untrusted: the
// schema hash covers only the schema region, so the region table, the catalog, the layout descriptor
// and the varlen offsets are all attacker- or corruption-controlled. Each case below takes a valid
// golden segment, patches one field, and asserts a FormatError — never a crash, never a silent
// misread. Run under a sanitizer these also pin that no out-of-bounds access happens first.
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "format/arena_format.hpp"
#include "format/layout_generated.h"
#include "format/segment_reader.hpp"
#include "test_framework.hpp"

namespace {

namespace fs = std::filesystem;

std::vector<std::uint8_t> read_file(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

void store_i64(std::vector<std::uint8_t> &b, std::size_t offset, std::int64_t v) {
    std::memcpy(b.data() + offset, &v, sizeof(v));
}
void store_i32(std::vector<std::uint8_t> &b, std::size_t offset, std::int32_t v) {
    std::memcpy(b.data() + offset, &v, sizeof(v));
}
std::int64_t load_i64(const std::vector<std::uint8_t> &b, std::size_t offset) {
    return arena::load_le<std::int64_t>(b.data(), offset);
}

std::size_t region_offset_field(int region) {
    return static_cast<std::size_t>(arena::fmt::HDR_REGION_TABLE + region * arena::fmt::REGION_ENTRY_SIZE);
}
std::size_t region_length_field(int region) {
    return region_offset_field(region) + 8;
}

// Writes `bytes` to a uniquely-named temp file and opens it as a segment. The file is removed even
// when open throws, which is the expected outcome in every test here.
struct Corrupted {
    fs::path path;

    explicit Corrupted(const std::vector<std::uint8_t> &bytes, const std::string &tag) {
        path = fs::temp_directory_path() / ("arrow_rdb_corrupt_" + tag + ".ascham");
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        out.write(reinterpret_cast<const char *>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    }
    ~Corrupted() {
        std::error_code ec;
        fs::remove(path, ec);
    }
    arena::SegmentReader open() const { return arena::SegmentReader::open(path.string()); }
};

std::vector<std::uint8_t> good() { return read_file(::testfw::golden_path("all_types")); }

// Byte offset of column `ordinal`'s element_width field inside the layout descriptor region.
// The flatcc reader hands back a pointer straight into the buffer (`_get_ptr`), so no wire-format
// walking is needed to corrupt one field in place. The source bytes are a valid golden.
std::size_t element_width_field(const std::vector<std::uint8_t> &b, int ordinal) {
    std::size_t layout_off =
        static_cast<std::size_t>(load_i64(b, region_offset_field(arena::fmt::REGION_LAYOUT)));
    io_ascham_flatbuf_LayoutDescriptor_table_t fb =
        io_ascham_flatbuf_LayoutDescriptor_as_root(b.data() + layout_off);
    io_ascham_flatbuf_ColumnLayout_table_t col = io_ascham_flatbuf_ColumnLayout_vec_at(
        io_ascham_flatbuf_LayoutDescriptor_columns(fb), static_cast<std::size_t>(ordinal));
    const std::int32_t* field = io_ascham_flatbuf_ColumnLayout_element_width_get_ptr(col);
    return static_cast<std::size_t>(reinterpret_cast<const std::uint8_t*>(field) - b.data());
}

}  // namespace

TEST(corrupt_rejects_truncated_file) {
    auto b = good();
    b.resize(b.size() / 2);  // keeps a valid header but cuts the data region away
    Corrupted c(b, "truncated");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_header_only_file) {
    auto b = good();
    b.resize(static_cast<std::size_t>(arena::fmt::HEADER_LENGTH));
    Corrupted c(b, "header_only");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_catalog_region_past_eof) {
    auto b = good();
    store_i64(b, region_offset_field(arena::fmt::REGION_CATALOG), 1LL << 40);
    Corrupted c(b, "catalog_past_eof");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_catalog_region_overflowing_length) {
    auto b = good();
    // offset + length would wrap if the check were written as an addition.
    store_i64(b, region_length_field(arena::fmt::REGION_CATALOG), 0x7fffffffffffffffLL);
    Corrupted c(b, "catalog_overflow");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_negative_region_offset) {
    auto b = good();
    store_i64(b, region_offset_field(arena::fmt::REGION_DATA), -4096);
    Corrupted c(b, "negative_region");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_misaligned_catalog) {
    auto b = good();
    std::int64_t off = load_i64(b, region_offset_field(arena::fmt::REGION_CATALOG));
    store_i64(b, region_offset_field(arena::fmt::REGION_CATALOG), off + 1);  // breaks atomic alignment
    Corrupted c(b, "misaligned_catalog");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_active_batch_count_beyond_catalog) {
    auto b = good();
    store_i64(b, static_cast<std::size_t>(arena::fmt::HDR_ACTIVE_BATCH_COUNT), 1LL << 30);
    Corrupted c(b, "batch_count");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_negative_active_batch_count) {
    auto b = good();
    store_i64(b, static_cast<std::size_t>(arena::fmt::HDR_ACTIVE_BATCH_COUNT), -1);
    Corrupted c(b, "negative_batch_count");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_batch_base_offset_past_eof) {
    auto b = good();
    std::int64_t catalog = load_i64(b, region_offset_field(arena::fmt::REGION_CATALOG));
    store_i64(b, static_cast<std::size_t>(catalog + arena::fmt::ENT_BASE_OFFSET), 1LL << 40);
    Corrupted c(b, "base_offset");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_row_count_above_batch_rows) {
    auto b = good();
    std::int64_t catalog = load_i64(b, region_offset_field(arena::fmt::REGION_CATALOG));
    std::int64_t rows = load_i64(b, static_cast<std::size_t>(catalog + arena::fmt::ENT_LENGTH));
    // Keep the in-progress bit, inflate the row count beyond the header's batch_rows.
    std::int64_t flag = rows & arena::fmt::IN_PROGRESS_BIT;
    store_i64(b, static_cast<std::size_t>(catalog + arena::fmt::ENT_LENGTH), flag | 1000);
    Corrupted c(b, "row_count");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_v1_format_version) {
    // Format v1 (bespoke layout codec) is no longer readable; the version gate must fail loudly
    // before any layout decode is attempted.
    auto b = good();
    store_i32(b, static_cast<std::size_t>(arena::fmt::HDR_FORMAT_VERSION), 1);
    Corrupted c(b, "v1_version");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_truncated_layout_region) {
    // A structurally damaged descriptor is caught by the flatbuffers verifier — a capability the
    // v1 hand-rolled codec never had beyond simple bounds checks.
    auto b = good();
    std::int64_t len = load_i64(b, region_length_field(arena::fmt::REGION_LAYOUT));
    store_i64(b, region_length_field(arena::fmt::REGION_LAYOUT), len / 2);
    Corrupted c(b, "layout_truncated");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_layout_identifier_mismatch) {
    auto b = good();
    std::size_t layout_off =
        static_cast<std::size_t>(load_i64(b, region_offset_field(arena::fmt::REGION_LAYOUT)));
    std::memcpy(b.data() + layout_off + 4, "XXXX", 4);  // the ALD2 file identifier
    Corrupted c(b, "layout_identifier");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_layout_disagreeing_with_header) {
    auto b = good();
    store_i64(b, static_cast<std::size_t>(arena::fmt::HDR_BATCH_STRIDE), 8192);  // layout still says 4096
    Corrupted c(b, "geometry");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_element_width_overrunning_capacity) {
    auto b = good();
    // Column 5 (i64) is 8 bytes wide with a capacity of 8 * batch_rows; widening it means the
    // declared capacity can no longer hold batch_rows values, and the fill path's bulk copy would
    // read (and write) past the end of both buffers.
    store_i32(b, element_width_field(b, 5), 64);
    Corrupted c(b, "element_width");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_non_positive_element_width) {
    auto b = good();
    store_i32(b, element_width_field(b, 5), 0);
    Corrupted c(b, "zero_width");
    CHECK_THROWS(c.open());
}

TEST(corrupt_rejects_inverted_varlen_offsets) {
    auto b = good();
    auto r = arena::SegmentReader::open(::testfw::golden_path("all_types"));
    const arena::ColumnLayout &sym = r.column(12);  // the VARLEN 'sym' column
    std::size_t offsets = static_cast<std::size_t>(r.batches()[0].base_offset + sym.offsets_offset);
    // offsets[1] < offsets[0]: a length of o1 - o0 would be negative and widen to a huge unsigned
    // byte count at the call site.
    store_i32(b, offsets + 0, 8);
    store_i32(b, offsets + 4, 2);
    Corrupted c(b, "varlen_inverted");
    auto reader = c.open();  // opens fine: offsets are data, checked on access
    CHECK_THROWS(reader.varlen(0, 0, 12));
}

TEST(corrupt_rejects_varlen_offset_past_capacity) {
    auto b = good();
    auto r = arena::SegmentReader::open(::testfw::golden_path("all_types"));
    const arena::ColumnLayout &sym = r.column(12);
    std::size_t offsets = static_cast<std::size_t>(r.batches()[0].base_offset + sym.offsets_offset);
    store_i32(b, offsets + 4, static_cast<std::int32_t>(sym.varlen_capacity_bytes + 1));
    Corrupted c(b, "varlen_past_capacity");
    auto reader = c.open();
    CHECK_THROWS(reader.varlen(0, 0, 12));
}

TEST(valid_golden_still_opens) {
    // The negative tests above are only meaningful if the unpatched file passes the same checks.
    auto b = good();
    Corrupted c(b, "unpatched");
    auto reader = c.open();
    CHECK_EQ(reader.column_count(), 14);
    CHECK_EQ(reader.header().batch_rows, 4);
}
