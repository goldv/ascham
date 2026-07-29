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

    // Invariant 7: recompute the hash over the embedded schema bytes and compare to the header. A
    // reader misinterpreting a layout produces plausible garbage — the worst failure mode — so a
    // mismatch is a hard failure, never a silent read.
    if (hi.schema_offset + hi.schema_length > static_cast<std::int64_t>(size)) {
        throw FormatError("schema region out of bounds: " + path);
    }
    auto digest = sha256(base + hi.schema_offset, static_cast<std::size_t>(hi.schema_length));
    if (std::memcmp(digest.data(), base + fmt::HDR_SCHEMA_SHA256, fmt::SHA256_LENGTH) != 0) {
        throw FormatError("schema hash mismatch: the embedded schema does not match the header: " + path);
    }

    if (hi.layout_offset + hi.layout_length > static_cast<std::int64_t>(size)) {
        throw FormatError("layout region out of bounds: " + path);
    }
    r.layout_ = LayoutDescriptor::decode(base + hi.layout_offset, hi.layout_length);

    // Freeze the catalog: acquire-load active_batch_count once, then each entry's length once
    // (segment-format.md "Reader snapshot protocol"). The length acquire happens-after every plain
    // write to the entry, so base_offset and (for sealed batches) stats are visible and consistent.
    hi.active_batch_count = acquire_i64(base, fmt::HDR_ACTIVE_BATCH_COUNT);
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
    return {bb + c.data_offset + o0, o1 - o0};
}

}  // namespace arena
