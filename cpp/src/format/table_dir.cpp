#include "table_dir.hpp"

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <regex>

#include "arena_format.hpp"

namespace arena {

namespace {

// Hinnant's civil-calendar arithmetic: days since 1970-01-01 from a proleptic-Gregorian date and
// back, pure integer — no OS timezone, no <ctime>.
std::int64_t days_from_civil(int y, unsigned m, unsigned d) {
    y -= m <= 2;
    const std::int64_t era = (y >= 0 ? y : y - 399) / 400;
    const unsigned yoe = static_cast<unsigned>(y - era * 400);
    const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
    const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + static_cast<std::int64_t>(doe) - 719468;
}

void civil_from_days(std::int64_t z, int& y, unsigned& m, unsigned& d) {
    z += 719468;
    const std::int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    const unsigned doe = static_cast<unsigned>(z - era * 146097);
    const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    const std::int64_t yy = static_cast<std::int64_t>(yoe) + era * 400;
    const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    const unsigned mp = (5 * doy + 2) / 153;
    d = doy - (153 * mp + 2) / 5 + 1;
    m = mp < 10 ? mp + 3 : mp - 9;
    y = static_cast<int>(yy) + (m <= 2);
}

constexpr int SEQUENCE_BITS = 15;
constexpr int CYCLE_BITS = 16;
constexpr std::int64_t SEQUENCE_MAX = (std::int64_t{1} << SEQUENCE_BITS) - 1;   // 32767
constexpr std::int64_t CYCLE_MAX = (std::int64_t{1} << CYCLE_BITS) - 1;         // 65535
constexpr std::int64_t EPOCH_MINUTES_MAX = (std::int64_t{1} << 32) - 1;

}  // namespace

bool parse_segment_name(const std::string& filename, SegmentName& out) {
    // <yyyyMMdd>.<seq>.ascham or <yyyyMMdd>.<HHmm>.<minutes>m.<seq>.ascham — the shared
    // pattern constant is the same string the Java SegmentDirectory compiles; *.tmp.* files created
    // mid-write do not match. Digit counts are bounded so stoi cannot overflow.
    static const std::regex pattern(fmt::SEGMENT_FILENAME_PATTERN);
    std::smatch m;
    if (!std::regex_match(filename, m, pattern)) {
        return false;
    }
    out.year_month_day = std::stoi(m[1].str());
    if (m[2].matched) {
        out.start_hhmm = std::stoi(m[2].str());
        out.cycle_minutes = std::stoi(m[3].str());
    } else {
        out.start_hhmm = 0;
        out.cycle_minutes = 1440;
    }
    out.sequence = std::stoi(m[4].str());
    return true;
}

bool encode_segment_id(const SegmentName& name, std::int64_t& id, std::string& error) {
    if (name.year_month_day == 0) {
        error = "name does not match the segment naming scheme";
        return false;
    }
    const int year = name.year_month_day / 10000;
    const unsigned month = static_cast<unsigned>((name.year_month_day / 100) % 100);
    const unsigned day = static_cast<unsigned>(name.year_month_day % 100);
    const std::int64_t days = days_from_civil(year, month, day);
    // The regex only checks digit counts, so validate the date by round-trip (rejects month 13,
    // day 0, Feb 30, ...). days_from_civil is only bijective over real dates.
    {
        int y2;
        unsigned m2, d2;
        civil_from_days(days, y2, m2, d2);
        if (y2 != year || m2 != month || d2 != day) {
            error = "'" + std::to_string(name.year_month_day) + "' is not a calendar date";
            return false;
        }
    }
    const int hh = name.start_hhmm / 100, mm = name.start_hhmm % 100;
    if (hh > 23 || mm > 59) {
        error = "'" + std::to_string(name.start_hhmm) + "' is not a time of day";
        return false;
    }
    const std::int64_t epoch_minutes = days * 1440 + hh * 60 + mm;
    if (epoch_minutes < 0 || epoch_minutes > EPOCH_MINUTES_MAX) {
        error = "interval start is before 1970";  // the upper bound is unreachable for 4-digit years
        return false;
    }
    if (name.cycle_minutes < 1 || name.cycle_minutes > CYCLE_MAX) {
        error = "cycle minutes " + std::to_string(name.cycle_minutes) + " outside 1.." +
                std::to_string(CYCLE_MAX);
        return false;
    }
    if (name.sequence < 0 || name.sequence > SEQUENCE_MAX) {
        error = "sequence " + std::to_string(name.sequence) + " outside 0.." +
                std::to_string(SEQUENCE_MAX);
        return false;
    }
    id = (epoch_minutes << (CYCLE_BITS + SEQUENCE_BITS)) |
         (static_cast<std::int64_t>(name.cycle_minutes) << SEQUENCE_BITS) |
         static_cast<std::int64_t>(name.sequence);
    return true;
}

bool decode_segment_id(std::int64_t id, SegmentName& out) {
    if (id < 0) {
        return false;
    }
    const std::int64_t cycle = (id >> SEQUENCE_BITS) & CYCLE_MAX;
    if (cycle == 0) {
        return false;
    }
    const std::int64_t epoch_minutes = id >> (CYCLE_BITS + SEQUENCE_BITS);
    int y;
    unsigned m, d;
    civil_from_days(epoch_minutes / 1440, y, m, d);
    if (y > 9999) {
        return false;  // encode's dates come from 8-digit yyyymmdd names
    }
    const int minute_of_day = static_cast<int>(epoch_minutes % 1440);
    out.year_month_day = y * 10000 + static_cast<int>(m) * 100 + static_cast<int>(d);
    out.start_hhmm = (minute_of_day / 60) * 100 + minute_of_day % 60;
    out.cycle_minutes = static_cast<int>(cycle);
    out.sequence = static_cast<int>(id & SEQUENCE_MAX);
    return true;
}

std::string segment_basename(const SegmentName& name) {
    char buf[64];
    if (name.start_hhmm == 0 && name.cycle_minutes == 1440) {
        std::snprintf(buf, sizeof buf, "%08d.%d.ascham", name.year_month_day, name.sequence);
    } else {
        std::snprintf(buf, sizeof buf, "%08d.%04d.%dm.%d.ascham", name.year_month_day,
                      name.start_hhmm, name.cycle_minutes, name.sequence);
    }
    return buf;
}

std::vector<SegmentName> list_segments(const std::string& table_dir) {
    std::vector<SegmentName> names;
    std::error_code ec;
    if (!std::filesystem::is_directory(table_dir, ec)) {
        return names;
    }
    for (const auto& entry : std::filesystem::directory_iterator(table_dir, ec)) {
        SegmentName s;
        if (parse_segment_name(entry.path().filename().string(), s)) {
            s.path = entry.path().string();
            names.push_back(std::move(s));
        }
    }
    std::sort(names.begin(), names.end());
    return names;
}

}  // namespace arena
