#include "layout.hpp"

#include "arena_format.hpp"
#include "format_error.hpp"

namespace arena {
namespace {

// Sequential little-endian cursor over the descriptor bytes (mirrors the Java LayoutCodec).
struct Cursor {
    const std::uint8_t* base;
    std::int64_t length;
    std::int64_t pos = 0;

    std::int32_t i32() {
        require(4);
        std::int32_t v = load_le<std::int32_t>(base, pos);
        pos += 4;
        return v;
    }
    std::int64_t i64() {
        require(8);
        std::int64_t v = load_le<std::int64_t>(base, pos);
        pos += 8;
        return v;
    }
    std::string str() {
        std::int32_t len = i32();
        require(len);
        std::string s(reinterpret_cast<const char*>(base + pos), static_cast<std::size_t>(len));
        pos += len;
        return s;
    }
    void require(std::int64_t n) {
        if (n < 0 || pos + n > length) {
            throw FormatError("layout descriptor truncated");
        }
    }
};

}  // namespace

LayoutDescriptor LayoutDescriptor::decode(const std::uint8_t* base, std::int64_t length) {
    Cursor c{base, length};
    LayoutDescriptor d;

    std::int32_t version = c.i32();
    if (version != fmt::LAYOUT_CODEC_VERSION) {
        throw FormatError("unsupported layout codec version " + std::to_string(version));
    }
    d.batch_rows = c.i32();
    d.batch_stride_bytes = c.i64();

    std::int32_t family_count = c.i32();
    d.families.reserve(static_cast<std::size_t>(family_count));
    for (std::int32_t i = 0; i < family_count; ++i) {
        d.families.push_back(c.str());
    }

    std::int32_t column_count = c.i32();
    d.columns.reserve(static_cast<std::size_t>(column_count));
    for (std::int32_t i = 0; i < column_count; ++i) {
        ColumnLayout col;
        col.name = c.str();
        col.ordinal = c.i32();
        col.kind = static_cast<PhysicalKind>(c.i32());
        col.family_id = c.i32();
        col.element_width = c.i32();
        col.validity_offset = c.i64();
        col.data_offset = c.i64();
        col.data_capacity_bytes = c.i64();
        col.offsets_offset = c.i64();
        col.varlen_capacity_bytes = c.i64();
        d.columns.push_back(std::move(col));
    }

    if (c.pos != length) {
        throw FormatError("layout descriptor length mismatch: declared " + std::to_string(length) +
                          ", consumed " + std::to_string(c.pos));
    }
    return d;
}

}  // namespace arena
