// Arena segment format constants and low-level load helpers.
//
// The authoritative byte contract is format/segment-format.md in the ascham repo. Everything here
// mirrors it exactly; nothing may change without a format-version bump. This subtree
// (src/format/) deliberately has NO DuckDB dependency — it is the reusable C++ arena reader.
#pragma once

#include <bit>
#include <cstdint>
#include <cstring>

#if defined(__cpp_lib_atomic_ref)
#include <atomic>
#endif

namespace arena {

// The format is little-endian throughout, matching x86-64 / aarch64 hosts (segment-format.md
// "Conventions"). We read values with memcpy (alignment-safe) and never byte-swap.
static_assert(std::endian::native == std::endian::little,
              "the arena format is little-endian; a big-endian host would need byte swapping");

namespace fmt {

// Header (segment-format.md "Header").
inline constexpr char MAGIC[8] = {'A', 'S', 'C', 'H', 'A', 'M', 'F', 'M'};
inline constexpr int MAGIC_LENGTH = 8;
// v2 (2026-08): the layout-descriptor region became a flatbuffer per format/Layout.fbs.
inline constexpr int FORMAT_VERSION = 2;
inline constexpr int HEADER_LENGTH = 4096;

inline constexpr int HDR_MAGIC = 0;
inline constexpr int HDR_FORMAT_VERSION = 8;
inline constexpr int HDR_HEADER_LENGTH = 12;
inline constexpr int HDR_SCHEMA_SHA256 = 16;   // 32 bytes
inline constexpr int HDR_SEGMENT_SEQUENCE = 48;
inline constexpr int HDR_ARENA_CAPACITY = 56;
inline constexpr int HDR_WRITER_EPOCH = 64;
inline constexpr int HDR_BATCH_ROWS = 72;
inline constexpr int HDR_BATCH_STRIDE = 80;
inline constexpr int HDR_HEARTBEAT = 128;          // ordered (release/acquire)
inline constexpr int HDR_ACTIVE_BATCH_COUNT = 192; // ordered (release/acquire)

// Region table: 5 (offset,length) int64 pairs starting at 256.
inline constexpr int HDR_REGION_TABLE = 256;
inline constexpr int REGION_ENTRY_SIZE = 16;
inline constexpr int REGION_SCHEMA = 0;
inline constexpr int REGION_LAYOUT = 1;
inline constexpr int REGION_CATALOG = 2;
inline constexpr int REGION_DATA = 3;
inline constexpr int REGION_FAMILY_WATERMARKS = 4;

// Catalog entry (segment-format.md "Batch catalog"): 64 bytes, one cache line.
inline constexpr int CATALOG_ENTRY_SIZE = 64;
inline constexpr int ENT_LENGTH = 0;        // ordered (release/acquire); bit 63 = in progress
inline constexpr int ENT_BASE_OFFSET = 8;
inline constexpr int ENT_TS_MIN = 16;
inline constexpr int ENT_TS_MAX = 24;
inline constexpr int ENT_STAT_MIN = 32;
inline constexpr int ENT_STAT_MAX = 40;
inline constexpr int ENT_SEAL_NANOS = 48;

inline constexpr std::int64_t IN_PROGRESS_BIT = std::int64_t{1} << 63;
inline constexpr std::int64_t ROW_COUNT_MASK = 0x7fffffffffffffffLL;

inline constexpr int SHA256_LENGTH = 32;

// Alignment (spec invariants 5 and 6).
inline constexpr int BUFFER_ALIGN = 64;
inline constexpr int PAGE_ALIGN = 4096;

// ascham.* metadata keys (Arrow schema custom_metadata; segment-format.md "Metadata keys").
// The writer validates these strictly; this reader consumes a subset and deliberately ignores
// the rest. Mirrors MetadataKeys.java.
inline constexpr const char* METADATA_PREFIX = "ascham.";
inline constexpr const char* META_TABLE = "ascham.table";
inline constexpr const char* META_SCHEMA_VERSION = "ascham.schema_version";
inline constexpr const char* META_BATCH_ROWS = "ascham.batch_rows";
inline constexpr const char* META_TIME_COLUMN = "ascham.time_column";
inline constexpr const char* META_STATS_COLUMN = "ascham.stats_column";
inline constexpr const char* META_VARLEN_BYTES = "ascham.varlen_bytes";
inline constexpr const char* META_SORT_KEY = "ascham.sort_key";
inline constexpr const char* META_FAMILY = "ascham.family";
inline constexpr const char* META_REF = "ascham.ref";

// Segment filename grammar: <yyyyMMdd>.<seq>.ascham (daily cycle) or
// <yyyyMMdd>.<HHmm>.<minutes>m.<seq>.ascham (sub-day). The {1,9} bounds keep every numeric group
// inside int32 before any parse; in-flight *.tmp.* files are excluded by non-match. Mirrors
// SegmentFormat.SEGMENT_FILENAME_PATTERN — the Java side compiles the same string.
inline constexpr const char* SEGMENT_FILENAME_PATTERN =
    R"re(^(\d{8})\.(?:(\d{4})\.(\d{1,9})m\.)?(\d{1,9})\.ascham$)re";

}  // namespace fmt

// Plain little-endian load of a trivially-copyable value at a byte offset.
template <class T>
inline T load_le(const std::uint8_t* base, std::size_t offset = 0) {
    T value;
    std::memcpy(&value, base + offset, sizeof(T));
    return value;
}

// Acquire load of an ordered int64 field (catalog length, active_batch_count, heartbeat). All
// ordered fields are 8-byte aligned by format construction, and SegmentReader::open validates the
// one offset that comes from the file (catalog_offset) before any acquire load through it, so this
// is well-defined. Provides the same acquire semantics as the Java ControlRegion's VarHandle
// getAcquire (segment-format.md "Concurrency contract").
//
// The const_cast is sound: the mapping is PROT_READ and only a load is ever performed. It exists
// because std::atomic_ref cannot bind to a const lvalue. libc++/AppleClang shipped atomic_ref late,
// hence the fallback to the GCC/Clang builtin, which loads through a const pointer directly.
inline std::int64_t acquire_i64(const std::uint8_t* base, std::size_t offset) {
#if defined(__cpp_lib_atomic_ref)
    auto* p = reinterpret_cast<std::int64_t*>(const_cast<std::uint8_t*>(base + offset));
    return std::atomic_ref<std::int64_t>(*p).load(std::memory_order_acquire);
#else
    const std::int64_t* p = reinterpret_cast<const std::int64_t*>(base + offset);
    return __atomic_load_n(p, __ATOMIC_ACQUIRE);
#endif
}

inline std::int64_t row_count_of(std::int64_t length) {
    return length & fmt::ROW_COUNT_MASK;
}

inline bool is_in_progress(std::int64_t length) {
    return (length & fmt::IN_PROGRESS_BIT) != 0;
}

}  // namespace arena
