#include "layout.hpp"

#include "arena_format.hpp"
#include "format_error.hpp"
#include "layout_generated.h"

namespace arena {

LayoutDescriptor LayoutDescriptor::decode(const std::uint8_t* base, std::int64_t length) {
    // The descriptor region is a flatbuffer per format/Layout.fbs (format v2+), read here from
    // mmap'd memory another process wrote — the verifier is the trust boundary and runs before
    // any accessor. Same flatcc runtime the vendored nanoarrow IPC decoder uses.
    int rc = io_ascham_flatbuf_LayoutDescriptor_verify_as_root_with_identifier(
        base, static_cast<size_t>(length), io_ascham_flatbuf_LayoutDescriptor_file_identifier);
    if (rc != 0) {
        throw FormatError(std::string("layout descriptor rejected by verifier: ") +
                          flatcc_verify_error_string(rc));
    }
    io_ascham_flatbuf_LayoutDescriptor_table_t fb =
        io_ascham_flatbuf_LayoutDescriptor_as_root(base);

    LayoutDescriptor d;
    d.batch_rows = io_ascham_flatbuf_LayoutDescriptor_batch_rows(fb);
    d.batch_stride_bytes = io_ascham_flatbuf_LayoutDescriptor_batch_stride_bytes(fb);

    flatbuffers_string_vec_t families = io_ascham_flatbuf_LayoutDescriptor_families(fb);
    size_t family_count = flatbuffers_string_vec_len(families);
    d.families.reserve(family_count);
    for (size_t i = 0; i < family_count; ++i) {
        flatbuffers_string_t family = flatbuffers_string_vec_at(families, i);
        d.families.emplace_back(family, flatbuffers_string_len(family));
    }

    io_ascham_flatbuf_ColumnLayout_vec_t columns = io_ascham_flatbuf_LayoutDescriptor_columns(fb);
    size_t column_count = io_ascham_flatbuf_ColumnLayout_vec_len(columns);
    d.columns.reserve(column_count);
    for (size_t i = 0; i < column_count; ++i) {
        io_ascham_flatbuf_ColumnLayout_table_t c = io_ascham_flatbuf_ColumnLayout_vec_at(columns, i);
        ColumnLayout col;
        flatbuffers_string_t name = io_ascham_flatbuf_ColumnLayout_name(c);
        col.name.assign(name, flatbuffers_string_len(name));
        col.ordinal = io_ascham_flatbuf_ColumnLayout_ordinal(c);
        // The .fbs enum is the wire-value authority; unknown values are outside the contract.
        std::int32_t kind = io_ascham_flatbuf_ColumnLayout_kind(c);
        if (kind < 0 || kind > static_cast<std::int32_t>(io_ascham_flatbuf_PhysicalKind_BoolBitmap)) {
            throw FormatError("layout descriptor column '" + col.name +
                              "': unknown physical kind " + std::to_string(kind));
        }
        col.kind = static_cast<PhysicalKind>(kind);
        col.family_id = io_ascham_flatbuf_ColumnLayout_family_id(c);
        col.element_width = io_ascham_flatbuf_ColumnLayout_element_width(c);
        col.validity_offset = io_ascham_flatbuf_ColumnLayout_validity_offset(c);
        col.data_offset = io_ascham_flatbuf_ColumnLayout_data_offset(c);
        col.data_capacity_bytes = io_ascham_flatbuf_ColumnLayout_data_capacity_bytes(c);
        col.offsets_offset = io_ascham_flatbuf_ColumnLayout_offsets_offset(c);
        col.varlen_capacity_bytes = io_ascham_flatbuf_ColumnLayout_varlen_capacity_bytes(c);
        d.columns.push_back(std::move(col));
    }
    return d;
}

}  // namespace arena
