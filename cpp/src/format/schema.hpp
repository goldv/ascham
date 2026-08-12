// Decodes the segment's embedded Arrow IPC schema message into logical column types and the
// `ascham.*` schema metadata the layout descriptor deliberately omits (logical type, decimal
// precision/scale, timestamp unit/timezone, and the time/stats column names for zone-map pushdown).
// Uses the vendored nanoarrow IPC decoder — the one external dependency in the reader core.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// Arrow C data interface struct, defined at global scope by nanoarrow (and by any other Arrow
// C library); forward-declared so this header stays free of the nanoarrow include.
struct ArrowSchema;

namespace arena {

enum class LogicalType {
    BOOL,
    INT8, INT16, INT32, INT64,
    UINT8, UINT16, UINT32, UINT64,
    FLOAT32, FLOAT64,
    DECIMAL128,
    DATE32,
    TIME64_NS,
    TIMESTAMP,
    FIXED_SIZE_BINARY,
    UTF8,
    BINARY
};

enum class TimestampUnit { NANO, MICRO };

struct ColumnType {
    std::string name;
    LogicalType type = LogicalType::INT64;
    std::int32_t decimal_precision = 0;   // DECIMAL128
    std::int32_t decimal_scale = 0;       // DECIMAL128
    std::int32_t fixed_size = 0;          // FIXED_SIZE_BINARY byte width
    TimestampUnit timestamp_unit = TimestampUnit::NANO;
    std::string timezone;                 // TIMESTAMP tz, empty if none
};

struct TableSchema {
    std::vector<ColumnType> columns;
    std::string time_column;   // ascham.time_column
    std::string stats_column;  // ascham.stats_column, empty if absent

    // Decodes the embedded Arrow IPC schema message (see SegmentReader::embedded_schema()).
    // Throws FormatError on a decode failure or a type outside the v1 profile.
    static TableSchema decode(const std::uint8_t* ipc_schema_message, std::size_t length);
};

// Maps one Arrow field (C ABI) to its v1 logical column type; throws FormatError outside the
// profile. Segment decoding goes through TableSchema::decode — this entry point exists so the
// type-profile conformance vectors can drive the mapping directly.
ColumnType map_field(const ::ArrowSchema* field);

}  // namespace arena
