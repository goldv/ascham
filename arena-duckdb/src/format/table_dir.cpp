#include "table_dir.hpp"

#include <algorithm>
#include <filesystem>
#include <regex>

namespace arena {

std::vector<SegmentName> list_segments(const std::string& table_dir) {
    std::vector<SegmentName> names;
    std::error_code ec;
    if (!std::filesystem::is_directory(table_dir, ec)) {
        return names;
    }
    // <8 digits>.<seq>.arena — the *.tmp.* files created mid-write do not match.
    static const std::regex pattern(R"(^(\d{8})\.(\d+)\.arena$)");
    for (const auto& entry : std::filesystem::directory_iterator(table_dir, ec)) {
        std::string filename = entry.path().filename().string();
        std::smatch m;
        if (std::regex_match(filename, m, pattern)) {
            SegmentName s;
            s.year_month_day = std::stoi(m[1].str());
            s.sequence = std::stoi(m[2].str());
            s.path = entry.path().string();
            names.push_back(std::move(s));
        }
    }
    std::sort(names.begin(), names.end());
    return names;
}

}  // namespace arena
