// The `arena` DuckDB extension entrypoint and the `arena_segments` diagnostic table function.
//
// `arena_segments(path)` exposes the batch catalog of a segment file or a table directory as SQL:
// one row per (segment, batch) with row counts, sealed flag, catalog zone-map stats, seal time,
// and the live heartbeat. It is the reader core (src/format/) surfaced through DuckDB — a fixed
// scalar schema, so it needs no Arrow-schema logical-type decode.
//
// The zero-copy `arena_scan` table function (dynamic schema, projection/filter pushdown) builds on
// this same reader core and lands next; see docs/duckdb-extension-design-plan.md.
#include "duckdb.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

#include <exception>
#include <filesystem>
#include <string>
#include <vector>

#include "format/format_error.hpp"
#include "format/segment_reader.hpp"
#include "format/table_dir.hpp"

namespace duckdb {
namespace {

struct SegRow {
    std::string path;
    int64_t segment_seq, writer_epoch, batch, rows;
    bool sealed;
    int64_t ts_min, ts_max, stat_min, stat_max, seal_nanos, heartbeat;
};

struct ArenaSegmentsBindData : public TableFunctionData {
    std::vector<SegRow> rows;
};

std::vector<std::string> ResolveSegmentPaths(const std::string &path) {
    namespace fs = std::filesystem;
    std::error_code ec;
    if (fs::is_directory(path, ec)) {
        std::vector<std::string> paths;
        for (auto &seg : arena::list_segments(path)) {
            paths.push_back(seg.path);
        }
        return paths;
    }
    return {path};  // a single segment file (e.g. a golden-corpus .bin)
}

unique_ptr<FunctionData> ArenaSegmentsBind(ClientContext &, TableFunctionBindInput &input,
                                           vector<LogicalType> &return_types, vector<string> &names) {
    auto path = input.inputs[0].GetValue<string>();
    auto result = make_uniq<ArenaSegmentsBindData>();
    try {
        for (auto &seg_path : ResolveSegmentPaths(path)) {
            auto reader = arena::SegmentReader::open(seg_path);
            int64_t heartbeat = reader.heartbeat_acquire();
            for (auto &b : reader.batches()) {
                result->rows.push_back(SegRow{seg_path, reader.header().segment_sequence,
                                              reader.header().writer_epoch, b.index, b.row_count, b.sealed,
                                              b.ts_min, b.ts_max, b.stat_min, b.stat_max, b.seal_nanos,
                                              heartbeat});
            }
        }
    } catch (const std::exception &e) {
        throw InvalidInputException("arena_segments: " + std::string(e.what()));
    }

    names = {"path", "segment_seq", "writer_epoch", "batch", "rows", "sealed",
             "ts_min", "ts_max", "stat_min", "stat_max", "seal_nanos", "heartbeat"};
    return_types = {LogicalType::VARCHAR, LogicalType::BIGINT, LogicalType::BIGINT, LogicalType::BIGINT,
                    LogicalType::BIGINT, LogicalType::BOOLEAN, LogicalType::BIGINT, LogicalType::BIGINT,
                    LogicalType::BIGINT, LogicalType::BIGINT, LogicalType::BIGINT, LogicalType::BIGINT};
    return std::move(result);
}

struct ArenaSegmentsState : public GlobalTableFunctionState {
    idx_t offset = 0;
};

unique_ptr<GlobalTableFunctionState> ArenaSegmentsInit(ClientContext &, TableFunctionInitInput &) {
    return make_uniq<ArenaSegmentsState>();
}

void ArenaSegmentsScan(ClientContext &, TableFunctionInput &data_p, DataChunk &output) {
    auto &bind = data_p.bind_data->Cast<ArenaSegmentsBindData>();
    auto &state = data_p.global_state->Cast<ArenaSegmentsState>();
    idx_t count = 0;
    while (state.offset < bind.rows.size() && count < STANDARD_VECTOR_SIZE) {
        const SegRow &r = bind.rows[state.offset++];
        output.SetValue(0, count, Value(r.path));
        output.SetValue(1, count, Value::BIGINT(r.segment_seq));
        output.SetValue(2, count, Value::BIGINT(r.writer_epoch));
        output.SetValue(3, count, Value::BIGINT(r.batch));
        output.SetValue(4, count, Value::BIGINT(r.rows));
        output.SetValue(5, count, Value::BOOLEAN(r.sealed));
        output.SetValue(6, count, Value::BIGINT(r.ts_min));
        output.SetValue(7, count, Value::BIGINT(r.ts_max));
        output.SetValue(8, count, Value::BIGINT(r.stat_min));
        output.SetValue(9, count, Value::BIGINT(r.stat_max));
        output.SetValue(10, count, Value::BIGINT(r.seal_nanos));
        output.SetValue(11, count, Value::BIGINT(r.heartbeat));
        ++count;
    }
    output.SetCardinality(count);
}

void RegisterArena(ExtensionLoader &loader) {
    TableFunction seg("arena_segments", {LogicalType::VARCHAR}, ArenaSegmentsScan, ArenaSegmentsBind,
                      ArenaSegmentsInit);
    loader.RegisterFunction(seg);
}

}  // namespace
}  // namespace duckdb

extern "C" {

DUCKDB_CPP_EXTENSION_ENTRY(arena, loader) {
    duckdb::RegisterArena(loader);
}
}
