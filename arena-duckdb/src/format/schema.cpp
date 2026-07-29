#include "schema.hpp"

#include <string>

#include "format_error.hpp"
#include "nanoarrow/nanoarrow.h"
#include "nanoarrow/nanoarrow_ipc.h"

namespace arena {
namespace {

std::string sv_to_string(ArrowStringView v) {
    return v.data ? std::string(v.data, static_cast<std::size_t>(v.size_bytes)) : std::string();
}

void read_table_metadata(const char* metadata, TableSchema& out) {
    if (metadata == nullptr) {
        return;
    }
    ArrowMetadataReader reader;
    if (ArrowMetadataReaderInit(&reader, metadata) != NANOARROW_OK) {
        return;
    }
    ArrowStringView key, value;
    while (ArrowMetadataReaderRead(&reader, &key, &value) == NANOARROW_OK) {
        std::string k = sv_to_string(key);
        if (k == "arena.time_column") {
            out.time_column = sv_to_string(value);
        } else if (k == "arena.stats_column") {
            out.stats_column = sv_to_string(value);
        }
    }
}

ColumnType map_column(const ArrowSchema* field, ArrowError& error) {
    ArrowSchemaView view;
    if (ArrowSchemaViewInit(&view, field, &error) != NANOARROW_OK) {
        throw FormatError(std::string("schema view: ") + error.message);
    }
    ColumnType col;
    col.name = field->name ? field->name : "";
    switch (view.type) {
    case NANOARROW_TYPE_BOOL: col.type = LogicalType::BOOL; break;
    case NANOARROW_TYPE_INT8: col.type = LogicalType::INT8; break;
    case NANOARROW_TYPE_INT16: col.type = LogicalType::INT16; break;
    case NANOARROW_TYPE_INT32: col.type = LogicalType::INT32; break;
    case NANOARROW_TYPE_INT64: col.type = LogicalType::INT64; break;
    case NANOARROW_TYPE_UINT8: col.type = LogicalType::UINT8; break;
    case NANOARROW_TYPE_UINT16: col.type = LogicalType::UINT16; break;
    case NANOARROW_TYPE_UINT32: col.type = LogicalType::UINT32; break;
    case NANOARROW_TYPE_UINT64: col.type = LogicalType::UINT64; break;
    case NANOARROW_TYPE_FLOAT: col.type = LogicalType::FLOAT32; break;
    case NANOARROW_TYPE_DOUBLE: col.type = LogicalType::FLOAT64; break;
    case NANOARROW_TYPE_DECIMAL128:
        col.type = LogicalType::DECIMAL128;
        col.decimal_precision = view.decimal_precision;
        col.decimal_scale = view.decimal_scale;
        break;
    case NANOARROW_TYPE_DATE32: col.type = LogicalType::DATE32; break;
    case NANOARROW_TYPE_TIME64: col.type = LogicalType::TIME64_NS; break;
    case NANOARROW_TYPE_TIMESTAMP:
        col.type = LogicalType::TIMESTAMP;
        col.timestamp_unit =
            view.time_unit == NANOARROW_TIME_UNIT_NANO ? TimestampUnit::NANO : TimestampUnit::MICRO;
        if (view.timezone) {
            col.timezone = view.timezone;
        }
        break;
    case NANOARROW_TYPE_FIXED_SIZE_BINARY:
        col.type = LogicalType::FIXED_SIZE_BINARY;
        col.fixed_size = view.fixed_size;
        break;
    case NANOARROW_TYPE_STRING: col.type = LogicalType::UTF8; break;
    case NANOARROW_TYPE_BINARY: col.type = LogicalType::BINARY; break;
    default:
        throw FormatError("column '" + col.name + "': type outside the v1 profile (" +
                          std::string(ArrowTypeString(view.type)) + ")");
    }
    return col;
}

}  // namespace

TableSchema TableSchema::decode(const std::uint8_t* ipc_schema_message, std::size_t length) {
    ArrowIpcDecoder decoder;
    ArrowIpcDecoderInit(&decoder);
    ArrowError error;
    error.message[0] = '\0';

    ArrowBufferView data;
    data.data.as_uint8 = ipc_schema_message;
    data.size_bytes = static_cast<int64_t>(length);

    ArrowSchema schema;
    schema.release = nullptr;
    if (ArrowIpcDecoderDecodeHeader(&decoder, data, &error) != NANOARROW_OK ||
        ArrowIpcDecoderDecodeSchema(&decoder, &schema, &error) != NANOARROW_OK) {
        ArrowIpcDecoderReset(&decoder);
        throw FormatError(std::string("embedded schema decode: ") + error.message);
    }
    ArrowIpcDecoderReset(&decoder);

    TableSchema result;
    try {
        read_table_metadata(schema.metadata, result);
        result.columns.reserve(static_cast<std::size_t>(schema.n_children));
        for (int64_t i = 0; i < schema.n_children; ++i) {
            result.columns.push_back(map_column(schema.children[i], error));
        }
    } catch (...) {
        if (schema.release) {
            schema.release(&schema);
        }
        throw;
    }
    if (schema.release) {
        schema.release(&schema);
    }
    return result;
}

}  // namespace arena
