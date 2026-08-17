// Type-profile conformance vectors: the v1 profile as one hand-authored, language-neutral table
// (conformance/type_profile_vectors.json). The Java
// writer pins TypeProfile.classify to the same file; this test pins the reader's map_field, so
// the two languages' hand-written type enumerations can no longer drift — an accepted row must
// map (with the expected fixed width), a rejected row must throw.
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <string>

#include "format/format_error.hpp"
#include "format/schema.hpp"
#include "nanoarrow/nanoarrow.h"
#include "test_framework.hpp"

namespace {

// Field extraction over the generated one-row-per-line JSON (no escapes; see the emitter).
bool str_field(const std::string &s, const std::string &key, std::string &out) {
    std::size_t k = s.find("\"" + key + "\": \"");
    if (k == std::string::npos) return false;
    std::size_t start = k + key.size() + 5;
    std::size_t end = s.find('"', start);
    if (end == std::string::npos) return false;
    out = s.substr(start, end - start);
    return true;
}

bool int_field(const std::string &s, const std::string &key, std::int64_t &out) {
    std::size_t k = s.find("\"" + key + "\": ");
    if (k == std::string::npos) return false;
    out = std::strtoll(s.c_str() + k + key.size() + 4, nullptr, 10);
    return true;
}

ArrowTimeUnit time_unit(const std::string &row) {
    std::string unit;
    if (!str_field(row, "unit", unit)) {
        ::testfw::fail("vector row has no unit: " + row, __FILE__, __LINE__);
    }
    if (unit == "NANOSECOND") return NANOARROW_TIME_UNIT_NANO;
    if (unit == "MICROSECOND") return NANOARROW_TIME_UNIT_MICRO;
    if (unit == "MILLISECOND") return NANOARROW_TIME_UNIT_MILLI;
    if (unit == "SECOND") return NANOARROW_TIME_UNIT_SECOND;
    ::testfw::fail("unknown time unit " + unit, __FILE__, __LINE__);
}

// Owns an ArrowSchema built to a vector row's description. The switch is test glue, not contract.
struct FieldSchema {
    ArrowSchema schema;

    explicit FieldSchema(const std::string &row) {
        std::string arrow;
        if (!str_field(row, "arrow", arrow)) {
            ::testfw::fail("vector row has no arrow type: " + row, __FILE__, __LINE__);
        }
        ArrowSchemaInit(&schema);
        std::int64_t precision = 0, scale = 0, width = 0;
        std::string tz, child;
        if (arrow == "Bool") set(NANOARROW_TYPE_BOOL);
        else if (arrow == "Int8") set(NANOARROW_TYPE_INT8);
        else if (arrow == "Int16") set(NANOARROW_TYPE_INT16);
        else if (arrow == "Int32") set(NANOARROW_TYPE_INT32);
        else if (arrow == "Int64") set(NANOARROW_TYPE_INT64);
        else if (arrow == "UInt8") set(NANOARROW_TYPE_UINT8);
        else if (arrow == "UInt16") set(NANOARROW_TYPE_UINT16);
        else if (arrow == "UInt32") set(NANOARROW_TYPE_UINT32);
        else if (arrow == "UInt64") set(NANOARROW_TYPE_UINT64);
        else if (arrow == "Float16") set(NANOARROW_TYPE_HALF_FLOAT);
        else if (arrow == "Float32") set(NANOARROW_TYPE_FLOAT);
        else if (arrow == "Float64") set(NANOARROW_TYPE_DOUBLE);
        else if (arrow == "Decimal128" || arrow == "Decimal256") {
            int_field(row, "precision", precision);
            int_field(row, "scale", scale);
            ok(ArrowSchemaSetTypeDecimal(&schema,
                                         arrow == "Decimal128" ? NANOARROW_TYPE_DECIMAL128
                                                               : NANOARROW_TYPE_DECIMAL256,
                                         static_cast<int32_t>(precision),
                                         static_cast<int32_t>(scale)));
        } else if (arrow == "Date32") set(NANOARROW_TYPE_DATE32);
        else if (arrow == "Date64") set(NANOARROW_TYPE_DATE64);
        else if (arrow == "Time32")
            ok(ArrowSchemaSetTypeDateTime(&schema, NANOARROW_TYPE_TIME32, time_unit(row), nullptr));
        else if (arrow == "Time64")
            ok(ArrowSchemaSetTypeDateTime(&schema, NANOARROW_TYPE_TIME64, time_unit(row), nullptr));
        else if (arrow == "Timestamp") {
            str_field(row, "timezone", tz);
            ok(ArrowSchemaSetTypeDateTime(&schema, NANOARROW_TYPE_TIMESTAMP, time_unit(row),
                                          tz.empty() ? nullptr : tz.c_str()));
        } else if (arrow == "FixedSizeBinary") {
            int_field(row, "byte-width", width);
            ok(ArrowSchemaSetTypeFixedSize(&schema, NANOARROW_TYPE_FIXED_SIZE_BINARY,
                                           static_cast<int32_t>(width)));
        } else if (arrow == "Utf8") set(NANOARROW_TYPE_STRING);
        else if (arrow == "Binary") set(NANOARROW_TYPE_BINARY);
        else if (arrow == "LargeUtf8") set(NANOARROW_TYPE_LARGE_STRING);
        else if (arrow == "LargeBinary") set(NANOARROW_TYPE_LARGE_BINARY);
        else if (arrow == "Duration")
            ok(ArrowSchemaSetTypeDateTime(&schema, NANOARROW_TYPE_DURATION, time_unit(row), nullptr));
        else if (arrow == "Interval") set(NANOARROW_TYPE_INTERVAL_MONTHS);
        else if (arrow == "Null") set(NANOARROW_TYPE_NA);
        else if (arrow == "List") {
            ok(ArrowSchemaSetType(&schema, NANOARROW_TYPE_LIST));
            ok(ArrowSchemaSetType(schema.children[0], NANOARROW_TYPE_INT32));
        } else if (arrow == "Struct") {
            ok(ArrowSchemaSetTypeStruct(&schema, 1));
            ok(ArrowSchemaSetType(schema.children[0], NANOARROW_TYPE_INT32));
        } else if (arrow == "Map") {
            ok(ArrowSchemaSetType(&schema, NANOARROW_TYPE_MAP));
            ok(ArrowSchemaSetType(schema.children[0]->children[0], NANOARROW_TYPE_STRING));
            ok(ArrowSchemaSetType(schema.children[0]->children[1], NANOARROW_TYPE_INT32));
        } else if (arrow == "Dictionary") {
            ok(ArrowSchemaSetType(&schema, NANOARROW_TYPE_INT32));  // index type
            ok(ArrowSchemaAllocateDictionary(&schema));
            ArrowSchemaInit(schema.dictionary);
            ok(ArrowSchemaSetType(schema.dictionary, NANOARROW_TYPE_STRING));
        } else {
            ::testfw::fail("vector glue does not know type " + arrow, __FILE__, __LINE__);
        }
        std::string id;
        str_field(row, "id", id);
        ok(ArrowSchemaSetName(&schema, id.c_str()));
    }

    ~FieldSchema() {
        if (schema.release) schema.release(&schema);
    }

    void set(ArrowType type) { ok(ArrowSchemaSetType(&schema, type)); }

    static void ok(ArrowErrorCode code) {
        if (code != NANOARROW_OK) {
            ::testfw::fail("nanoarrow schema construction failed", __FILE__, __LINE__);
        }
    }
};

// The reader's physical kind and fixed width for a mapped logical type — the reader-core half of
// what the vector's "kind"/"width" columns assert.
std::string kind_of(const arena::ColumnType &col) {
    switch (col.type) {
    case arena::LogicalType::BOOL: return "BOOL_BITMAP";
    case arena::LogicalType::UTF8:
    case arena::LogicalType::BINARY: return "VARLEN";
    default: return "FIXED";
    }
}

int width_of(const arena::ColumnType &col) {
    switch (col.type) {
    case arena::LogicalType::INT8:
    case arena::LogicalType::UINT8: return 1;
    case arena::LogicalType::INT16:
    case arena::LogicalType::UINT16: return 2;
    case arena::LogicalType::INT32:
    case arena::LogicalType::UINT32:
    case arena::LogicalType::FLOAT32:
    case arena::LogicalType::DATE32: return 4;
    case arena::LogicalType::INT64:
    case arena::LogicalType::UINT64:
    case arena::LogicalType::FLOAT64:
    case arena::LogicalType::TIME64_NS:
    case arena::LogicalType::TIMESTAMP: return 8;
    case arena::LogicalType::DECIMAL128: return 16;
    case arena::LogicalType::FIXED_SIZE_BINARY: return col.fixed_size;
    default: return 0;
    }
}

}  // namespace

TEST(type_profile_matches_vectors) {
    std::ifstream in(::testfw::conformance_dir() + "/type_profile_vectors.json");
    if (!in) {
        ::testfw::fail("cannot open conformance/type_profile_vectors.json", __FILE__, __LINE__);
    }
    int accepted = 0, rejected = 0;
    bool in_accepted = false, in_rejected = false;
    std::string line;
    while (std::getline(in, line)) {
        if (line.find("\"accepted\": [") != std::string::npos) {
            in_accepted = true;
            in_rejected = false;
            continue;
        }
        if (line.find("\"rejected\": [") != std::string::npos) {
            in_accepted = false;
            in_rejected = true;
            continue;
        }
        std::string id;
        if (!str_field(line, "id", id)) continue;

        if (in_accepted) {
            FieldSchema fs(line);
            arena::ColumnType col = arena::map_field(&fs.schema);
            std::string kind;
            CHECK(str_field(line, "kind", kind));
            if (kind_of(col) != kind) {
                ::testfw::fail("vector '" + id + "': reader maps to kind " + kind_of(col) +
                                   ", vectors say " + kind,
                               __FILE__, __LINE__);
            }
            std::int64_t width = 0;
            if (int_field(line, "width", width) && width_of(col) != width) {
                ::testfw::fail("vector '" + id + "': reader width " + std::to_string(width_of(col)) +
                                   ", vectors say " + std::to_string(width),
                               __FILE__, __LINE__);
            }
            ++accepted;
        } else if (in_rejected) {
            FieldSchema fs(line);
            bool threw = false;
            try {
                arena::map_field(&fs.schema);
            } catch (const arena::FormatError &) {
                threw = true;
            }
            if (!threw) {
                ::testfw::fail("vector '" + id + "': reader accepted a type outside the v1 profile",
                               __FILE__, __LINE__);
            }
            ++rejected;
        }
    }
    CHECK(accepted >= 24);
    CHECK(rejected >= 16);
}
