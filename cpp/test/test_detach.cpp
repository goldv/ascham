// The detach watermark file: grammar strictness, atomic publish, boundary semantics, and its
// invisibility to the segment lister. Filesystem-only — the query-surface behavior lives in
// test/sql/rdb_detach.test (read-only fixtures) and test/cpp_live (mutating end-to-end).
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <string>
#include <unistd.h>

#include "format/detach.hpp"
#include "format/table_dir.hpp"
#include "test_framework.hpp"

namespace {

namespace fs = std::filesystem;

fs::path fresh_dir(const std::string& name) {
    fs::path dir = fs::temp_directory_path() / ("arena_detach_" + name);
    fs::remove_all(dir);
    fs::create_directories(dir);
    return dir;
}

void write_raw(const fs::path& path, const std::string& content) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(content.data(), static_cast<std::streamsize>(content.size()));
}

arena::SegmentName parsed(const std::string& basename) {
    arena::SegmentName n;
    CHECK(arena::parse_segment_name(basename, n));
    return n;
}

}  // namespace

TEST(detach_watermark_roundtrip) {
    fs::path dir = fresh_dir("roundtrip");
    std::int64_t w = -1;
    CHECK(!arena::read_detach_watermark(dir.string(), w));  // absent: nothing detached

    arena::write_detach_watermark(dir.string(), 42);
    CHECK(fs::exists(dir / arena::DETACH_FILENAME));
    CHECK(arena::read_detach_watermark(dir.string(), w));
    CHECK_EQ(w, 42);

    arena::write_detach_watermark(dir.string(), 63251468019302400LL);  // overwrite with a real id
    CHECK(arena::read_detach_watermark(dir.string(), w));
    CHECK_EQ(w, 63251468019302400LL);

    arena::write_detach_watermark(dir.string(), 0);  // zero is a valid boundary
    CHECK(arena::read_detach_watermark(dir.string(), w));
    CHECK_EQ(w, 0);
    fs::remove_all(dir);
}

TEST(detach_watermark_clear) {
    fs::path dir = fresh_dir("clear");
    arena::clear_detach_watermark(dir.string());  // absent file is success
    arena::write_detach_watermark(dir.string(), 7);
    arena::clear_detach_watermark(dir.string());
    CHECK(!fs::exists(dir / arena::DETACH_FILENAME));
    std::int64_t w = -1;
    CHECK(!arena::read_detach_watermark(dir.string(), w));
    arena::clear_detach_watermark(dir.string());  // idempotent
    fs::remove_all(dir);
}

TEST(detach_watermark_malformed_is_hard_error) {
    fs::path dir = fresh_dir("malformed");
    fs::path file = dir / arena::DETACH_FILENAME;
    std::int64_t w = -1;
    // A present-but-unreadable watermark must never read as "no watermark": that would silently
    // re-attach everything. Every malformation is a hard error.
    for (const char* content : {
             "garbage\n",                        // wrong magic
             "ascham-detach v2\n42\n",           // wrong version
             "ascham-detach v1\n",               // missing watermark line
             "ascham-detach v1\n42",             // unterminated watermark line
             "ascham-detach v1\nforty\n",        // non-numeric
             "ascham-detach v1\n-42\n",          // negative
             "ascham-detach v1\n042\n",          // leading zeros
             "ascham-detach v1\n42\nextra\n",    // trailing bytes
             "ascham-detach v1\r\n42\r\n",       // CRLF
             "ascham-detach v1\n99999999999999999999\n",  // overflows int64
         }) {
        write_raw(file, content);
        CHECK_THROWS(arena::read_detach_watermark(dir.string(), w));
    }
    fs::remove_all(dir);
}

TEST(detach_file_never_lists_as_segment) {
    fs::path dir = fresh_dir("listing");
    write_raw(dir / "20260101.0.ascham", "x");
    write_raw(dir / "20260101.1.ascham", "x");
    arena::write_detach_watermark(dir.string(), 5);
    write_raw(dir / ".detach.tmp.123", "stale");  // a crashed publish attempt
    CHECK_EQ(arena::list_segments(dir.string()).size(), 2u);
    fs::remove_all(dir);
}

TEST(detach_write_survives_stale_tmp) {
    fs::path dir = fresh_dir("staletmp");
    // A stale tmp from this very pid (crash + pid reuse) is unlinked before the O_EXCL create.
    write_raw(dir / (std::string(arena::DETACH_FILENAME) + ".tmp." + std::to_string(::getpid())),
              "stale");
    arena::write_detach_watermark(dir.string(), 9);
    std::int64_t w = -1;
    CHECK(arena::read_detach_watermark(dir.string(), w));
    CHECK_EQ(w, 9);
    fs::remove_all(dir);
}

TEST(segment_is_detached_boundary) {
    arena::SegmentName seg = parsed("20260101.1.ascham");
    std::int64_t id = 0;
    std::string error;
    CHECK(arena::encode_segment_id(seg, id, error));

    CHECK(arena::segment_is_detached(seg, id));       // inclusive: id == W is detached
    CHECK(arena::segment_is_detached(seg, id + 1));
    CHECK(!arena::segment_is_detached(seg, id - 1));

    // A name that does not encode is never detached — the scans error on it at bind instead.
    arena::SegmentName overflow = parsed("20260101.0000.999999m.0.ascham");
    CHECK(!arena::segment_is_detached(overflow, std::int64_t{1} << 62));
    arena::SegmentName unparsed;  // year_month_day == 0
    CHECK(!arena::segment_is_detached(unparsed, std::int64_t{1} << 62));
}
