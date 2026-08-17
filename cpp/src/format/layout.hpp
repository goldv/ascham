// Decoded layout descriptor: the per-column byte layout within a batch. Read from the segment's
// layout-descriptor region — a flatbuffer per format/Layout.fbs (format v2+) — so the reader needs
// no build-time coupling to the writer. All offsets are batch-relative and 64-byte aligned.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace arena {

enum class PhysicalKind : int { FIXED = 0, VARLEN = 1, BOOL_BITMAP = 2 };

struct ColumnLayout {
    std::string name;
    int ordinal = 0;
    PhysicalKind kind = PhysicalKind::FIXED;
    int family_id = 0;
    int element_width = 0;             // fixed width in bytes; 0 for VARLEN/BOOL_BITMAP
    std::int64_t validity_offset = 0;
    std::int64_t data_offset = 0;
    std::int64_t data_capacity_bytes = 0;
    std::int64_t offsets_offset = -1;  // -1 if not varlen
    std::int64_t varlen_capacity_bytes = 0;

    bool is_varlen() const { return kind == PhysicalKind::VARLEN; }
    bool is_bool() const { return kind == PhysicalKind::BOOL_BITMAP; }
};

struct LayoutDescriptor {
    int batch_rows = 0;
    std::int64_t batch_stride_bytes = 0;
    std::vector<std::string> families;
    std::vector<ColumnLayout> columns;

    // Decodes the descriptor from LayoutCodec bytes at [base, base+length). Throws FormatError on a
    // bad codec version or a length mismatch.
    static LayoutDescriptor decode(const std::uint8_t* base, std::int64_t length);
};

}  // namespace arena
