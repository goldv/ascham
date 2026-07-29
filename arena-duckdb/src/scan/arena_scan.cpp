// arena_scan(path): the columnar table function. Bind decodes the segment's logical schema (via the
// reader core + nanoarrow) into DuckDB types; the scan fills DuckDB vectors from the mapped segment
// buffers batch by batch. v1 is sequential and correctness-first (copy per chunk); zero-copy vector
// wrapping, filter/projection pushdown, and parallelism are the next milestone.
#include "duckdb.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <cstring>
#include <exception>
#include <filesystem>
#include <string>
#include <vector>

#include "format/format_error.hpp"
#include "format/layout.hpp"
#include "format/schema.hpp"
#include "format/segment_reader.hpp"
#include "format/table_dir.hpp"

namespace duckdb {
namespace {

std::vector<std::string> ResolveSegmentPaths(const std::string &path) {
    std::error_code ec;
    if (std::filesystem::is_directory(path, ec)) {
        std::vector<std::string> paths;
        for (auto &seg : arena::list_segments(path)) {
            paths.push_back(seg.path);
        }
        return paths;
    }
    return {path};
}

// arena logical type -> DuckDB type. Notes locked here (see docs/duckdb-extension-design-plan.md §5):
//  - ns timestamps -> TIMESTAMP_NS (DuckDB has no ns-with-tz type, so a tz is dropped; UTC convention)
//  - us timestamps -> TIMESTAMP / TIMESTAMP_TZ
//  - Time64(ns)    -> TIME (us); the ns value is /1000 at fill time
//  - FixedSizeBinary/Binary -> BLOB
LogicalType MapType(const arena::ColumnType &c) {
    switch (c.type) {
    case arena::LogicalType::BOOL: return LogicalType::BOOLEAN;
    case arena::LogicalType::INT8: return LogicalType::TINYINT;
    case arena::LogicalType::INT16: return LogicalType::SMALLINT;
    case arena::LogicalType::INT32: return LogicalType::INTEGER;
    case arena::LogicalType::INT64: return LogicalType::BIGINT;
    case arena::LogicalType::UINT8: return LogicalType::UTINYINT;
    case arena::LogicalType::UINT16: return LogicalType::USMALLINT;
    case arena::LogicalType::UINT32: return LogicalType::UINTEGER;
    case arena::LogicalType::UINT64: return LogicalType::UBIGINT;
    case arena::LogicalType::FLOAT32: return LogicalType::FLOAT;
    case arena::LogicalType::FLOAT64: return LogicalType::DOUBLE;
    case arena::LogicalType::DECIMAL128: return LogicalType::DECIMAL(c.decimal_precision, c.decimal_scale);
    case arena::LogicalType::DATE32: return LogicalType::DATE;
    case arena::LogicalType::TIME64_NS: return LogicalType::TIME;
    case arena::LogicalType::TIMESTAMP:
        if (c.timestamp_unit == arena::TimestampUnit::NANO) {
            return LogicalType::TIMESTAMP_NS;
        }
        return c.timezone.empty() ? LogicalType::TIMESTAMP : LogicalType::TIMESTAMP_TZ;
    case arena::LogicalType::FIXED_SIZE_BINARY: return LogicalType::BLOB;
    case arena::LogicalType::UTF8: return LogicalType::VARCHAR;
    case arena::LogicalType::BINARY: return LogicalType::BLOB;
    }
    throw InternalException("arena_scan: unmapped arena type");
}

struct Work {
    int reader;
    int batch;
    int64_t rows;
};

struct ArenaScanBindData : public TableFunctionData {
    std::vector<arena::SegmentReader> readers;
    arena::TableSchema schema;
    std::vector<Work> work;
};

unique_ptr<FunctionData> ArenaScanBind(ClientContext &, TableFunctionBindInput &input,
                                       vector<LogicalType> &return_types, vector<string> &names) {
    auto path = input.inputs[0].GetValue<string>();
    auto bind = make_uniq<ArenaScanBindData>();
    try {
        for (auto &seg : ResolveSegmentPaths(path)) {
            bind->readers.push_back(arena::SegmentReader::open(seg));
        }
        if (bind->readers.empty()) {
            throw arena::FormatError("no segments found at '" + path + "'");
        }
        auto [schema_ptr, schema_len] = bind->readers[0].embedded_schema();
        bind->schema = arena::TableSchema::decode(schema_ptr, static_cast<std::size_t>(schema_len));
        for (int ri = 0; ri < static_cast<int>(bind->readers.size()); ++ri) {
            for (auto &b : bind->readers[ri].batches()) {
                if (b.row_count > 0) {  // skip the trailing empty in-progress batch
                    bind->work.push_back(Work{ri, b.index, b.row_count});
                }
            }
        }
    } catch (const std::exception &e) {
        throw InvalidInputException("arena_scan: " + std::string(e.what()));
    }
    for (auto &col : bind->schema.columns) {
        names.push_back(col.name);
        return_types.push_back(MapType(col));
    }
    return std::move(bind);
}

struct ArenaScanState : public GlobalTableFunctionState {
    idx_t work_idx = 0;
    int64_t row_offset = 0;
    idx_t MaxThreads() const override { return 1; }  // v1 sequential
};

unique_ptr<GlobalTableFunctionState> ArenaScanInit(ClientContext &, TableFunctionInitInput &) {
    return make_uniq<ArenaScanState>();
}

void FillColumn(const arena::SegmentReader &reader, const arena::ColumnType &logical, int ordinal,
                int batch, int64_t start, idx_t count, Vector &vec) {
    // Validity: arena bitmap -> DuckDB ValidityMask, per row (v1).
    auto &validity = FlatVector::Validity(vec);
    for (idx_t i = 0; i < count; ++i) {
        if (!reader.is_valid(batch, static_cast<int>(start + i), ordinal)) {
            validity.SetInvalid(i);
        }
    }

    const arena::ColumnLayout &phys = reader.column(ordinal);
    switch (logical.type) {
    case arena::LogicalType::BOOL: {
        auto out = FlatVector::GetData<bool>(vec);
        for (idx_t i = 0; i < count; ++i) {
            out[i] = reader.boolean(batch, static_cast<int>(start + i), ordinal);
        }
        break;
    }
    case arena::LogicalType::TIME64_NS: {
        auto out = FlatVector::GetData<int64_t>(vec);  // DuckDB TIME is microseconds
        for (idx_t i = 0; i < count; ++i) {
            out[i] = reader.fixed<int64_t>(batch, static_cast<int>(start + i), ordinal) / 1000;
        }
        break;
    }
    case arena::LogicalType::DECIMAL128: {
        // Copy the low `width` bytes of each 16-byte arena decimal (LE), matching DuckDB's backing
        // width (INT16/32/64/128) — the value's low bytes are identical for any precision.
        idx_t width = GetTypeIdSize(vec.GetType().InternalType());
        data_ptr_t out = FlatVector::GetData(vec);  // raw bytes: the vector is INT16/32/64/128-backed
        for (idx_t i = 0; i < count; ++i) {
            std::memcpy(out + i * width, reader.fixed_ptr(batch, static_cast<int>(start + i), ordinal), width);
        }
        break;
    }
    case arena::LogicalType::UTF8: {
        auto out = FlatVector::GetData<string_t>(vec);
        for (idx_t i = 0; i < count; ++i) {
            auto [ptr, len] = reader.varlen(batch, static_cast<int>(start + i), ordinal);
            out[i] = StringVector::AddString(vec, reinterpret_cast<const char *>(ptr), static_cast<idx_t>(len));
        }
        break;
    }
    case arena::LogicalType::BINARY:
    case arena::LogicalType::FIXED_SIZE_BINARY: {
        auto out = FlatVector::GetData<string_t>(vec);
        bool fixed = logical.type == arena::LogicalType::FIXED_SIZE_BINARY;
        for (idx_t i = 0; i < count; ++i) {
            const uint8_t *ptr;
            idx_t len;
            if (fixed) {
                ptr = reader.fixed_ptr(batch, static_cast<int>(start + i), ordinal);
                len = static_cast<idx_t>(phys.element_width);
            } else {
                auto v = reader.varlen(batch, static_cast<int>(start + i), ordinal);
                ptr = v.first;
                len = static_cast<idx_t>(v.second);
            }
            out[i] = StringVector::AddStringOrBlob(vec, reinterpret_cast<const char *>(ptr), len);
        }
        break;
    }
    default: {
        // Fixed-width primitives with matching arena/DuckDB widths (int/uint/float/date/timestamp):
        // one contiguous copy per chunk.
        int width = phys.element_width;
        std::memcpy(FlatVector::GetData(vec), reader.fixed_ptr(batch, static_cast<int>(start), ordinal),
                    static_cast<std::size_t>(count) * width);
        break;
    }
    }
}

void ArenaScanFunc(ClientContext &, TableFunctionInput &input, DataChunk &output) {
    auto &bind = input.bind_data->Cast<ArenaScanBindData>();
    auto &state = input.global_state->Cast<ArenaScanState>();
    if (state.work_idx >= bind.work.size()) {
        output.SetCardinality(0);
        return;
    }
    const Work &w = bind.work[state.work_idx];
    const arena::SegmentReader &reader = bind.readers[static_cast<std::size_t>(w.reader)];
    idx_t count = MinValue<idx_t>(static_cast<idx_t>(w.rows - state.row_offset), STANDARD_VECTOR_SIZE);

    for (idx_t c = 0; c < output.ColumnCount(); ++c) {
        FillColumn(reader, bind.schema.columns[c], static_cast<int>(c), w.batch, state.row_offset, count,
                   output.data[c]);
    }
    output.SetCardinality(count);

    state.row_offset += count;
    if (state.row_offset >= w.rows) {
        ++state.work_idx;
        state.row_offset = 0;
    }
}

}  // namespace

void RegisterArenaScan(ExtensionLoader &loader) {
    TableFunction scan("arena_scan", {LogicalType::VARCHAR}, ArenaScanFunc, ArenaScanBind, ArenaScanInit);
    loader.RegisterFunction(scan);
}

}  // namespace duckdb
