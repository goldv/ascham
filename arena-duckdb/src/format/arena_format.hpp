// Arena segment format constants and low-level load helpers.
//
// The authoritative byte contract is docs/segment-format.md in the ito-db repo. Everything here
// mirrors it exactly; nothing may change without a format-version bump. This subtree
// (src/format/) deliberately has NO DuckDB dependency — it is the reusable C++ arena reader.
#pragma once

#include <bit>
#include <cstdint>
#include <cstring>

namespace arena {

// The format is little-endian throughout, matching x86-64 / aarch64 hosts (segment-format.md
// "Conventions"). We read values with memcpy (alignment-safe) and never byte-swap.
static_assert(std::endian::native == std::endian::little,
              "the arena format is little-endian; a big-endian host would need byte swapping");

namespace fmt {

// Header (segment-format.md "Header").
inline constexpr char MAGIC[8] = {'A', 'R', 'E', 'N', 'A', 'F', 'M', 'T'};
inline constexpr int MAGIC_LENGTH = 8;
inline constexpr int FORMAT_VERSION = 1;
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

// Layout descriptor codec (segment-format.md "Layout descriptor region").
inline constexpr int LAYOUT_CODEC_VERSION = 1;

}  // namespace fmt

// Plain little-endian load of a trivially-copyable value at a byte offset.
template <class T>
inline T load_le(const std::uint8_t* base, std::size_t offset = 0) {
    T value;
    std::memcpy(&value, base + offset, sizeof(T));
    return value;
}

// Acquire load of an ordered int64 field (catalog length, active_batch_count, heartbeat). All
// ordered fields are 8-byte aligned by format construction, so this is well-defined. We use the
// compiler builtin rather than std::atomic_ref because the mapping is const (read-only) — the
// builtin loads through a const pointer cleanly and provides the same acquire semantics as the
// Java ControlRegion's VarHandle getAcquire (segment-format.md "Concurrency contract").
inline std::int64_t acquire_i64(const std::uint8_t* base, std::size_t offset) {
    const std::int64_t* p = reinterpret_cast<const std::int64_t*>(base + offset);
    return __atomic_load_n(p, __ATOMIC_ACQUIRE);
}

inline std::int64_t row_count_of(std::int64_t length) {
    return length & fmt::ROW_COUNT_MASK;
}

inline bool is_in_progress(std::int64_t length) {
    return (length & fmt::IN_PROGRESS_BIT) != 0;
}

}  // namespace arena
