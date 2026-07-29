// arena_scan(path): the columnar table function.
//
// Bind decodes the segment's logical schema (reader core + nanoarrow) into DuckDB types and builds a
// per-(segment, batch) work list. The scan fills DuckDB vectors from the mapped segment buffers.
//
// D4: projection pushdown (only requested columns materialized), filter pushdown as catalog zone
// maps (sealed batches whose time/stats [min,max] cannot intersect a constant filter are skipped
// before scanning — pushdown is an optimization; DuckDB re-applies every filter row-exactly), and a
// parallel work list claimed via an atomic cursor. v1 fills per chunk (copy); zero-copy vector
// wrapping is a later step.
#include "duckdb.hpp"
#include "duckdb/main/extension/extension_loader.hpp"
#include "duckdb/planner/filter/conjunction_filter.hpp"
#include "duckdb/planner/filter/constant_filter.hpp"
#include "duckdb/planner/table_filter.hpp"
#include "duckdb/planner/table_filter_state.hpp"
#include "duckdb/storage/table/column_segment.hpp"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <filesystem>
#include <limits>
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

// One scan work unit: a (segment, batch) with the catalog zone-map stats copied out for pushdown.
struct Work {
    int reader;
    int batch;
    int64_t rows;
    bool sealed;
    int64_t ts_min, ts_max, stat_min, stat_max;
};

struct ArenaScanBindData : public TableFunctionData {
    std::vector<arena::SegmentReader> readers;
    arena::TableSchema schema;
    std::vector<Work> work;
    int time_ordinal = -1;    // arena ordinal of arena.time_column
    int stats_ordinal = -1;   // arena ordinal of arena.stats_column, -1 if none
};

int find_column(const arena::TableSchema &s, const std::string &name) {
    for (int i = 0; i < static_cast<int>(s.columns.size()); ++i) {
        if (s.columns[static_cast<std::size_t>(i)].name == name) return i;
    }
    return -1;
}

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
        bind->time_ordinal = find_column(bind->schema, bind->schema.time_column);
        bind->stats_ordinal =
            bind->schema.stats_column.empty() ? -1 : find_column(bind->schema, bind->schema.stats_column);

        for (int ri = 0; ri < static_cast<int>(bind->readers.size()); ++ri) {
            for (auto &b : bind->readers[ri].batches()) {
                if (b.row_count > 0) {  // skip the trailing empty in-progress batch
                    bind->work.push_back(Work{ri, b.index, b.row_count, b.sealed,
                                              b.ts_min, b.ts_max, b.stat_min, b.stat_max});
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

// --- Filter pushdown: extract an inclusive [lo, hi] on a zone-map column from a table filter. ---

struct Range {
    int64_t lo = std::numeric_limits<int64_t>::min();
    int64_t hi = std::numeric_limits<int64_t>::max();
    bool narrowed = false;
};

// Raw int64 of a filter constant: a timestamp's internal int64 (in the column's unit) or a widened
// signed integer. Returns false if it cannot be represented (e.g. unsigned > INT64_MAX) — no prune.
bool constant_raw(const Value &v, bool is_timestamp, int64_t &out) {
    try {
        out = is_timestamp ? v.GetValueUnsafe<int64_t>() : v.GetValue<int64_t>();
        return true;
    } catch (...) {
        return false;
    }
}

void narrow(const ConstantFilter &cf, bool is_timestamp, Range &r) {
    int64_t c;
    if (!constant_raw(cf.constant, is_timestamp, c)) return;
    switch (cf.comparison_type) {
    case ExpressionType::COMPARE_EQUAL:
        r.lo = MaxValue(r.lo, c); r.hi = MinValue(r.hi, c); r.narrowed = true; break;
    case ExpressionType::COMPARE_GREATERTHANOREQUALTO:
        r.lo = MaxValue(r.lo, c); r.narrowed = true; break;
    case ExpressionType::COMPARE_GREATERTHAN:
        if (c != std::numeric_limits<int64_t>::max()) { r.lo = MaxValue(r.lo, c + 1); r.narrowed = true; } break;
    case ExpressionType::COMPARE_LESSTHANOREQUALTO:
        r.hi = MinValue(r.hi, c); r.narrowed = true; break;
    case ExpressionType::COMPARE_LESSTHAN:
        if (c != std::numeric_limits<int64_t>::min()) { r.hi = MinValue(r.hi, c - 1); r.narrowed = true; } break;
    default: break;
    }
}

void extract_range(const TableFilter &f, bool is_timestamp, Range &r) {
    switch (f.filter_type) {
    case TableFilterType::CONSTANT_COMPARISON:
        narrow(f.Cast<ConstantFilter>(), is_timestamp, r);
        break;
    case TableFilterType::CONJUNCTION_AND:
        for (auto &child : f.Cast<ConjunctionAndFilter>().child_filters) {
            extract_range(*child, is_timestamp, r);
        }
        break;
    default:
        break;  // OR / IS NULL / IN / etc.: conservative, no pruning
    }
}

// --- Parallel state ---

struct ArenaScanGlobalState : public GlobalTableFunctionState {
    vector<column_t> column_ids;
    optional_ptr<TableFilterSet> filters;
    vector<Work> work;  // after zone-map pruning
    std::atomic<idx_t> next_work{0};
    idx_t threads = 1;
    idx_t MaxThreads() const override { return threads; }
};

struct ArenaScanLocalState : public LocalTableFunctionState {
    bool active = false;
    idx_t work_idx = 0;
    int64_t row_offset = 0;
};

unique_ptr<GlobalTableFunctionState> ArenaScanInitGlobal(ClientContext &, TableFunctionInitInput &input) {
    auto &bind = input.bind_data->Cast<ArenaScanBindData>();
    auto g = make_uniq<ArenaScanGlobalState>();
    g->column_ids = input.column_ids;
    g->filters = input.filters;

    Range time_range, stat_range;
    if (input.filters) {
        for (auto &entry : input.filters->filters) {
            column_t col = input.column_ids[entry.first];
            if (static_cast<int>(col) == bind.time_ordinal) {
                extract_range(*entry.second, /*is_timestamp=*/true, time_range);
            } else if (static_cast<int>(col) == bind.stats_ordinal) {
                extract_range(*entry.second, /*is_timestamp=*/false, stat_range);
            }
        }
    }

    for (auto &w : bind.work) {
        if (w.sealed) {  // in-progress batches have unpublished stats — never zone-map-skip them
            if (time_range.narrowed && (w.ts_max < time_range.lo || w.ts_min > time_range.hi)) continue;
            if (stat_range.narrowed && (w.stat_max < stat_range.lo || w.stat_min > stat_range.hi)) continue;
        }
        g->work.push_back(w);
    }
    g->threads = MaxValue<idx_t>(1, g->work.size());

    if (std::getenv("ARENA_SCAN_DEBUG")) {
        std::fprintf(stderr, "arena_scan: kept %zu of %zu batches after zone-map pruning\n",
                     g->work.size(), bind.work.size());
    }
    return std::move(g);
}

unique_ptr<LocalTableFunctionState> ArenaScanInitLocal(ExecutionContext &, TableFunctionInitInput &,
                                                       GlobalTableFunctionState *) {
    return make_uniq<ArenaScanLocalState>();
}

void FillColumn(const arena::SegmentReader &reader, const arena::ColumnType &logical, int ordinal,
                int batch, int64_t start, idx_t count, Vector &vec) {
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
        idx_t width = GetTypeIdSize(vec.GetType().InternalType());
        data_ptr_t out = FlatVector::GetData(vec);
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
        // Fixed-width primitives with matching arena/DuckDB widths: one contiguous copy per chunk.
        int width = phys.element_width;
        std::memcpy(FlatVector::GetData(vec), reader.fixed_ptr(batch, static_cast<int>(start), ordinal),
                    static_cast<std::size_t>(count) * width);
        break;
    }
    }
}

// Apply the pushed-down filters row-exactly. DuckDB removes pushed filters from the plan (it trusts
// the scan to apply them), so this is required for correctness — zone-map batch pruning above is
// only an optimization on top. Uses DuckDB's own filter applicator, so every filter kind is handled.
void ApplyFilters(ClientContext &context, ArenaScanGlobalState &g, DataChunk &output) {
    if (!g.filters || g.filters->filters.empty()) {
        return;
    }
    idx_t count = output.size();
    if (count == 0) {
        return;
    }
    // One selection narrowed across all filters (FilterSelection reads it as the current candidate
    // set and compacts it), starting from identity; then slice the whole chunk once.
    SelectionVector sel(count);
    for (idx_t i = 0; i < count; ++i) {
        sel.set_index(i, i);
    }
    idx_t approved = count;
    for (auto &entry : g.filters->filters) {
        Vector &vec = output.data[entry.first];  // filters are keyed by projection (column_ids) index
        UnifiedVectorFormat vdata;
        vec.ToUnifiedFormat(count, vdata);
        auto state = TableFilterState::Initialize(context, *entry.second);
        ColumnSegment::FilterSelection(sel, vec, vdata, *entry.second, *state, count, approved);
        if (approved == 0) {
            break;
        }
    }
    if (approved < count) {
        output.Slice(sel, approved);
    }
    output.SetCardinality(approved);
}

void ArenaScanFunc(ClientContext &context, TableFunctionInput &input, DataChunk &output) {
    auto &bind = input.bind_data->Cast<ArenaScanBindData>();
    auto &g = input.global_state->Cast<ArenaScanGlobalState>();
    auto &l = input.local_state->Cast<ArenaScanLocalState>();

    // Claim the next work item when the current one is exhausted (or on first call).
    if (!l.active || l.row_offset >= g.work[l.work_idx].rows) {
        idx_t claimed = g.next_work.fetch_add(1);
        if (claimed >= g.work.size()) {
            output.SetCardinality(0);
            return;
        }
        l.active = true;
        l.work_idx = claimed;
        l.row_offset = 0;
    }

    const Work &w = g.work[l.work_idx];
    const arena::SegmentReader &reader = bind.readers[static_cast<std::size_t>(w.reader)];
    idx_t count = MinValue<idx_t>(static_cast<idx_t>(w.rows - l.row_offset), STANDARD_VECTOR_SIZE);

    for (idx_t i = 0; i < g.column_ids.size(); ++i) {
        int ordinal = static_cast<int>(g.column_ids[i]);  // projection pushdown
        FillColumn(reader, bind.schema.columns[static_cast<std::size_t>(ordinal)], ordinal, w.batch,
                   l.row_offset, count, output.data[i]);
    }
    output.SetCardinality(count);
    l.row_offset += count;  // source rows consumed (before filtering reduces the output)

    ApplyFilters(context, g, output);
}

}  // namespace

void RegisterArenaScan(ExtensionLoader &loader) {
    TableFunction scan("arena_scan", {LogicalType::VARCHAR}, ArenaScanFunc, ArenaScanBind,
                       ArenaScanInitGlobal, ArenaScanInitLocal);
    scan.projection_pushdown = true;
    scan.filter_pushdown = true;
    loader.RegisterFunction(scan);
}

}  // namespace duckdb
