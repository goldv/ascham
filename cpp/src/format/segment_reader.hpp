// The reader-core façade: opens a segment file, verifies it (magic, version, schema hash), decodes
// the layout, freezes a catalog snapshot, and exposes physical column values. No DuckDB dependency.
//
// Snapshot semantics match the Java SnapshotReader exactly (segment-format.md "Concurrency
// contract"): active_batch_count and every catalog length are acquire-loaded once at open and never
// re-read. Rows below a batch's frozen row count are immutable for the life of the segment
// (invariant 1), so the value accessors are always consistent.
#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "layout.hpp"
#include "mapped_file.hpp"

namespace arena {

struct SegmentHeaderInfo {
    std::int64_t segment_sequence = 0;
    std::int64_t arena_capacity = 0;
    std::int64_t writer_epoch = 0;
    std::int64_t batch_rows = 0;
    std::int64_t batch_stride = 0;
    std::int64_t active_batch_count = 0;  // frozen at open (acquire)
    // Copy of the header's schema hash. Identifies the schema across segments: a directory or list
    // scan requires every segment to carry the same hash (a rotation mid-list is a hard error).
    std::uint8_t schema_sha256[32] = {};
    std::int64_t schema_offset = 0, schema_length = 0;
    std::int64_t layout_offset = 0, layout_length = 0;
    std::int64_t catalog_offset = 0, catalog_length = 0;
    std::int64_t data_offset = 0, data_length = 0;
};

struct BatchInfo {
    int index = 0;
    std::int64_t base_offset = 0;  // segment-relative base of the batch's data
    std::int64_t row_count = 0;
    bool sealed = false;
    // Catalog stats — meaningful only when sealed (unpublished for in-progress batches).
    std::int64_t ts_min = 0, ts_max = 0, stat_min = 0, stat_max = 0, seal_nanos = 0;
};

class SegmentReader {
public:
    // Maps and verifies a segment. Throws FormatError on bad magic/version/hash, std::system_error
    // on I/O failure. The returned reader owns the mapping and the frozen snapshot.
    static SegmentReader open(const std::string& path);

    const SegmentHeaderInfo& header() const { return header_; }
    const LayoutDescriptor& layout() const { return layout_; }
    const std::vector<BatchInfo>& batches() const { return batches_; }
    int column_count() const { return static_cast<int>(layout_.columns.size()); }
    const ColumnLayout& column(int ordinal) const { return layout_.columns[static_cast<std::size_t>(ordinal)]; }

    // Re-reads the live heartbeat (acquire) for liveness checks; everything else is frozen.
    std::int64_t heartbeat_acquire() const;

    // The embedded canonical Arrow IPC schema message bytes (decode via TableSchema::decode).
    std::pair<const std::uint8_t*, std::int64_t> embedded_schema() const;

    // --- Physical value accessors. `batch` indexes batches(); `row < batches()[batch].row_count`. ---

    bool is_valid(int batch, int row, int col) const;
    const std::uint8_t* fixed_ptr(int batch, int row, int col) const;

    template <class T>
    T fixed(int batch, int row, int col) const {
        T v;
        std::memcpy(&v, fixed_ptr(batch, row, col), sizeof(T));
        return v;
    }

    bool boolean(int batch, int row, int col) const;                        // BOOL_BITMAP data bit
    std::pair<const std::uint8_t*, std::int32_t> varlen(int batch, int row, int col) const;

private:
    const std::uint8_t* batch_base(int batch) const;

    MappedFile file_;
    SegmentHeaderInfo header_;
    LayoutDescriptor layout_;
    std::vector<BatchInfo> batches_;
};

}  // namespace arena
