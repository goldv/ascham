// Enumerates a table's segment files, oldest-first by (interval start, cycle length, seq):
//   <dir>/<yyyyMMdd>.<seq>.ascham                       daily roll cycle
//   <dir>/<yyyyMMdd>.<HHmm>.<minutes>m.<seq>.ascham     sub-day roll cycle (UTC interval start)
// Mirrors the Java SegmentDirectory naming; skips in-progress temp files (*.tmp.*).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace arena {

struct SegmentName {
    int year_month_day = 0;    // yyyymmdd of the UTC interval start, for ordering
    int start_hhmm = 0;        // HHmm of the interval start; 0 for daily names
    int cycle_minutes = 1440;  // roll cycle length; 1440 for daily names
    int sequence = 0;
    std::string path;

    bool operator<(const SegmentName& o) const {
        if (year_month_day != o.year_month_day) return year_month_day < o.year_month_day;
        if (start_hhmm != o.start_hhmm) return start_hhmm < o.start_hhmm;
        if (cycle_minutes != o.cycle_minutes) return cycle_minutes < o.cycle_minutes;
        return sequence < o.sequence;
    }
};

// Lists segments in `table_dir`, oldest-first. Returns empty if the directory does not exist.
std::vector<SegmentName> list_segments(const std::string& table_dir);

// Parses a segment filename (no directory part) into `out`'s naming fields; `out.path` is left
// untouched. Returns false — leaving the fields as they were — if the name does not match the
// segment naming scheme (e.g. a golden-corpus .bin file).
bool parse_segment_name(const std::string& filename, SegmentName& out);

// Packs a segment's naming fields into one non-negative int64 whose integer order equals
// SegmentName's ordering — bits 62-31 UTC epoch-minutes of the interval start, bits 30-15
// cycle_minutes, bits 14-0 sequence. The filename regex admits values these widths cannot hold
// (and calendar-invalid dates), so encoding validates: date 1970-01-01..9999-12-31 and real,
// HHmm a real time of day, cycle_minutes 1..65535, sequence 0..32767. Returns false with `error`
// naming the offending field when the name is unparsed (year_month_day == 0) or out of range.
bool encode_segment_id(const SegmentName& name, std::int64_t& id, std::string& error);

// Inverse of encode_segment_id: false for any id no encode could have produced, so the two are
// exact inverses over encode's range. `out.path` is left untouched.
bool decode_segment_id(std::int64_t id, SegmentName& out);

// Canonical basename for naming fields: the daily form when the interval starts at midnight with a
// 1440-minute cycle, the sub-day form otherwise (the writer never spells a daily cycle the long
// way, so decode_segment_id + segment_basename round-trips every encoded name).
std::string segment_basename(const SegmentName& name);

}  // namespace arena
