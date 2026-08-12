#include "segment_reader.hpp"

#include <cstring>

#include "arena_format.hpp"
#include "format_error.hpp"
#include "sha256.hpp"

namespace arena {
namespace {

std::int64_t region_offset(const std::uint8_t* h, int region) {
    return load_le<std::int64_t>(h, fmt::HDR_REGION_TABLE + region * fmt::REGION_ENTRY_SIZE);
}
std::int64_t region_length(const std::uint8_t* h, int region) {
    return load_le<std::int64_t>(h, fmt::HDR_REGION_TABLE + region * fmt::REGION_ENTRY_SIZE + 8);
}

// Every offset and length below is read from the file and is therefore untrusted: a truncated,
// corrupt or hostile segment must produce a FormatError, never an out-of-bounds read. The schema
// hash covers only the schema region, so nothing else here is protected by it.
//
// Written as `length > limit - offset` rather than `offset + length > limit` so a hostile pair
// near INT64_MAX cannot overflow into a passing comparison.
void require_in_bounds(std::int64_t offset, std::int64_t length, std::int64_t limit,
                       const char* what, const std::string& path) {
    if (offset < 0 || length < 0 || offset > limit || length > limit - offset) {
        throw FormatError(std::string(what) + " out of bounds (offset " + std::to_string(offset) +
                          ", length " + std::to_string(length) + ", limit " + std::to_string(limit) +
                          "): " + path);
    }
}

// Validates that every buffer a column can address stays inside one batch stride. Read extents are
// what a reader actually touches: validity is one bit per row, fixed data is width*rows, a bool
// data bitmap is one bit per row, and a varlen column adds an (rows+1) int32 offsets buffer.
void validate_layout(const LayoutDescriptor& d, std::int64_t batch_stride, const std::string& path) {
    const std::int64_t rows = d.batch_rows;
    const std::int64_t bitmap_bytes = (rows + 7) / 8;
    for (const ColumnLayout& c : d.columns) {
        const std::string where = "column '" + c.name + "'";
        require_in_bounds(c.validity_offset, bitmap_bytes, batch_stride,
                          (where + " validity buffer").c_str(), path);
        require_in_bounds(c.data_offset, c.data_capacity_bytes, batch_stride,
                          (where + " data buffer").c_str(), path);
        switch (c.kind) {
            case PhysicalKind::FIXED:
                if (c.element_width <= 0) {
                    throw FormatError(where + " has non-positive element width " +
                                      std::to_string(c.element_width) + ": " + path);
                }
                if (c.element_width > 0 && rows > c.data_capacity_bytes / c.element_width) {
                    throw FormatError(where + " data capacity holds fewer than batch_rows values: " + path);
                }
                break;
            case PhysicalKind::BOOL_BITMAP:
                if (c.data_capacity_bytes < bitmap_bytes) {
                    throw FormatError(where + " bool bitmap smaller than batch_rows bits: " + path);
                }
                break;
            case PhysicalKind::VARLEN:
                require_in_bounds(c.offsets_offset, (rows + 1) * 4, batch_stride,
                                  (where + " offsets buffer").c_str(), path);
                require_in_bounds(c.data_offset, c.varlen_capacity_bytes, batch_stride,
                                  (where + " varlen data buffer").c_str(), path);
                break;
            default:
                throw FormatError(where + " has unknown physical kind " +
                                  std::to_string(static_cast<int>(c.kind)) + ": " + path);
        }
    }
}

}  // namespace

SegmentReader SegmentReader::open(const std::string& path) {
    SegmentReader r;
    r.file_ = MappedFile::open(path);
    const std::uint8_t* base = r.file_.data();
    const std::size_t size = r.file_.size();

    if (size < static_cast<std::size_t>(fmt::HEADER_LENGTH)) {
        throw FormatError("segment smaller than header: " + path);
    }
    if (std::memcmp(base + fmt::HDR_MAGIC, fmt::MAGIC, fmt::MAGIC_LENGTH) != 0) {
        throw FormatError("bad segment magic: " + path);
    }
    std::int32_t version = load_le<std::int32_t>(base, fmt::HDR_FORMAT_VERSION);
    if (version != fmt::FORMAT_VERSION) {
        throw FormatError("unsupported format version " + std::to_string(version) + ": " + path);
    }

    SegmentHeaderInfo& hi = r.header_;
    hi.segment_sequence = load_le<std::int64_t>(base, fmt::HDR_SEGMENT_SEQUENCE);
    hi.arena_capacity = load_le<std::int64_t>(base, fmt::HDR_ARENA_CAPACITY);
    hi.writer_epoch = load_le<std::int64_t>(base, fmt::HDR_WRITER_EPOCH);
    hi.batch_rows = load_le<std::int64_t>(base, fmt::HDR_BATCH_ROWS);
    hi.batch_stride = load_le<std::int64_t>(base, fmt::HDR_BATCH_STRIDE);
    hi.schema_offset = region_offset(base, fmt::REGION_SCHEMA);
    hi.schema_length = region_length(base, fmt::REGION_SCHEMA);
    hi.layout_offset = region_offset(base, fmt::REGION_LAYOUT);
    hi.layout_length = region_length(base, fmt::REGION_LAYOUT);
    hi.catalog_offset = region_offset(base, fmt::REGION_CATALOG);
    hi.catalog_length = region_length(base, fmt::REGION_CATALOG);
    hi.data_offset = region_offset(base, fmt::REGION_DATA);
    hi.data_length = region_length(base, fmt::REGION_DATA);

    const std::int64_t ssize = static_cast<std::int64_t>(size);
    require_in_bounds(hi.schema_offset, hi.schema_length, ssize, "schema region", path);
    require_in_bounds(hi.layout_offset, hi.layout_length, ssize, "layout region", path);
    require_in_bounds(hi.catalog_offset, hi.catalog_length, ssize, "catalog region", path);
    require_in_bounds(hi.data_offset, hi.data_length, ssize, "data region", path);

    if (hi.batch_rows < 0 || hi.batch_stride < 0) {
        throw FormatError("negative batch_rows or batch_stride: " + path);
    }
    // acquire_i64 reinterpret_casts to int64_t*, so the catalog base must be 8-byte aligned for the
    // per-entry length loads below to be well-defined. Guaranteed by format construction (the region
    // is 64-byte aligned), but the value comes from the file, so it is checked rather than assumed.
    if (hi.catalog_offset % 8 != 0) {
        throw FormatError("catalog region is not 8-byte aligned: " + path);
    }

    // Invariant 7: recompute the hash over the embedded schema bytes and compare to the header. A
    // reader misinterpreting a layout produces plausible garbage — the worst failure mode — so a
    // mismatch is a hard failure, never a silent read.
    auto digest = sha256(base + hi.schema_offset, static_cast<std::size_t>(hi.schema_length));
    if (std::memcmp(digest.data(), base + fmt::HDR_SCHEMA_SHA256, fmt::SHA256_LENGTH) != 0) {
        throw FormatError("schema hash mismatch: the embedded schema does not match the header: " + path);
    }
    std::memcpy(hi.schema_sha256, base + fmt::HDR_SCHEMA_SHA256, fmt::SHA256_LENGTH);

    r.layout_ = LayoutDescriptor::decode(base + hi.layout_offset, hi.layout_length);

    // The layout region is not covered by the schema hash, so it is cross-checked against the header
    // before any of its offsets are used to address data.
    if (r.layout_.batch_rows != hi.batch_rows || r.layout_.batch_stride_bytes != hi.batch_stride) {
        throw FormatError("layout descriptor disagrees with the header on batch geometry (layout " +
                          std::to_string(r.layout_.batch_rows) + " rows / " +
                          std::to_string(r.layout_.batch_stride_bytes) + " stride, header " +
                          std::to_string(hi.batch_rows) + " / " + std::to_string(hi.batch_stride) +
                          "): " + path);
    }
    validate_layout(r.layout_, hi.batch_stride, path);

    // Freeze the catalog: acquire-load active_batch_count once, then each entry's length once
    // (segment-format.md "Reader snapshot protocol"). The length acquire happens-after every plain
    // write to the entry, so base_offset and (for sealed batches) stats are visible and consistent.
    hi.active_batch_count = acquire_i64(base, fmt::HDR_ACTIVE_BATCH_COUNT);
    if (hi.active_batch_count < 0 ||
        hi.active_batch_count > hi.catalog_length / fmt::CATALOG_ENTRY_SIZE) {
        throw FormatError("active_batch_count " + std::to_string(hi.active_batch_count) +
                          " exceeds the catalog region's capacity: " + path);
    }
    int count = static_cast<int>(hi.active_batch_count);
    r.batches_.reserve(static_cast<std::size_t>(count));
    for (int k = 0; k < count; ++k) {
        std::int64_t entry = hi.catalog_offset + static_cast<std::int64_t>(k) * fmt::CATALOG_ENTRY_SIZE;
        std::int64_t length = acquire_i64(base, static_cast<std::size_t>(entry) + fmt::ENT_LENGTH);
        BatchInfo b;
        b.index = k;
        b.row_count = row_count_of(length);
        b.sealed = !is_in_progress(length);
        b.base_offset = load_le<std::int64_t>(base, static_cast<std::size_t>(entry) + fmt::ENT_BASE_OFFSET);
        b.ts_min = load_le<std::int64_t>(base, static_cast<std::size_t>(entry) + fmt::ENT_TS_MIN);
        b.ts_max = load_le<std::int64_t>(base, static_cast<std::size_t>(entry) + fmt::ENT_TS_MAX);
        b.stat_min = load_le<std::int64_t>(base, static_cast<std::size_t>(entry) + fmt::ENT_STAT_MIN);
        b.stat_max = load_le<std::int64_t>(base, static_cast<std::size_t>(entry) + fmt::ENT_STAT_MAX);
        b.seal_nanos = load_le<std::int64_t>(base, static_cast<std::size_t>(entry) + fmt::ENT_SEAL_NANOS);
        if (b.row_count > hi.batch_rows) {
            throw FormatError("batch " + std::to_string(k) + " declares " + std::to_string(b.row_count) +
                              " rows, more than batch_rows " + std::to_string(hi.batch_rows) + ": " + path);
        }
        // The whole stride must be mapped: readers address any buffer within it, and invariant 6
        // permits reading past the row count up to the batch's padded end.
        require_in_bounds(b.base_offset, hi.batch_stride, ssize,
                          ("batch " + std::to_string(k) + " data").c_str(), path);
        r.batches_.push_back(b);
    }
    return r;
}

std::int64_t SegmentReader::heartbeat_acquire() const {
    return acquire_i64(file_.data(), fmt::HDR_HEARTBEAT);
}

std::pair<const std::uint8_t*, std::int64_t> SegmentReader::embedded_schema() const {
    return {file_.data() + header_.schema_offset, header_.schema_length};
}

const std::uint8_t* SegmentReader::batch_base(int batch) const {
    return file_.data() + batches_[static_cast<std::size_t>(batch)].base_offset;
}

bool SegmentReader::is_valid(int batch, int row, int col) const {
    const ColumnLayout& c = column(col);
    const std::uint8_t* validity = batch_base(batch) + c.validity_offset;
    return (validity[row >> 3] & (1u << (row & 7))) != 0;
}

const std::uint8_t* SegmentReader::fixed_ptr(int batch, int row, int col) const {
    const ColumnLayout& c = column(col);
    return batch_base(batch) + c.data_offset + static_cast<std::int64_t>(row) * c.element_width;
}

bool SegmentReader::boolean(int batch, int row, int col) const {
    const ColumnLayout& c = column(col);
    const std::uint8_t* bitmap = batch_base(batch) + c.data_offset;  // bool data is a bitmap
    return (bitmap[row >> 3] & (1u << (row & 7))) != 0;
}

std::pair<const std::uint8_t*, std::int32_t> SegmentReader::varlen(int batch, int row, int col) const {
    const ColumnLayout& c = column(col);
    const std::uint8_t* bb = batch_base(batch);
    const std::uint8_t* offsets = bb + c.offsets_offset;
    std::int32_t o0 = load_le<std::int32_t>(offsets, static_cast<std::size_t>(row) * 4);
    std::int32_t o1 = load_le<std::int32_t>(offsets, static_cast<std::size_t>(row + 1) * 4);
    // The offsets buffer is data, not metadata, so it is untrusted. Without this an inverted pair
    // yields a negative length that callers widen to a ~2^64 byte count. Two compares per value,
    // negligible against the copy that follows.
    if (o0 < 0 || o1 < o0 || static_cast<std::int64_t>(o1) > c.varlen_capacity_bytes) {
        throw FormatError("varlen offsets out of range for column '" + c.name + "' (row " +
                          std::to_string(row) + ": [" + std::to_string(o0) + ", " +
                          std::to_string(o1) + "), capacity " +
                          std::to_string(c.varlen_capacity_bytes) + ")");
    }
    return {bb + c.data_offset + o0, o1 - o0};
}

}  // namespace arena
