// Enumerates a table's segment files: <dir>/<yyyyMMdd>.<seq>.arena, oldest-first by (day, seq).
// Mirrors the Java SegmentDirectory naming; skips in-progress temp files (*.tmp.*).
#pragma once

#include <string>
#include <vector>

namespace arena {

struct SegmentName {
    int year_month_day = 0;  // yyyymmdd as an int, for ordering
    int sequence = 0;
    std::string path;

    bool operator<(const SegmentName& o) const {
        if (year_month_day != o.year_month_day) return year_month_day < o.year_month_day;
        return sequence < o.sequence;
    }
};

// Lists segments in `table_dir`, oldest-first. Returns empty if the directory does not exist.
std::vector<SegmentName> list_segments(const std::string& table_dir);

}  // namespace arena
