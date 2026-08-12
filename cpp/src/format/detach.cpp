#include "detach.hpp"

#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>

#include "format_error.hpp"

namespace arena {

namespace {

constexpr const char* MAGIC_LINE = "ascham-detach v1";

std::string detach_path(const std::string& table_dir) {
    return table_dir + "/" + DETACH_FILENAME;
}

[[noreturn]] void malformed(const std::string& path, const std::string& why) {
    throw FormatError("detach watermark '" + path + "' is malformed (" + why +
                      "); refusing to treat it as absent — remove or rewrite it deliberately");
}

}  // namespace

bool read_detach_watermark(const std::string& table_dir, std::int64_t& watermark) {
    const std::string path = detach_path(table_dir);
    std::ifstream in(path, std::ios::binary);
    if (!in.is_open()) {
        return false;  // absent: nothing detached
    }
    std::ostringstream buf;
    buf << in.rdbuf();
    if (in.bad()) {
        throw FormatError("detach watermark '" + path + "': read failed");
    }
    const std::string content = buf.str();

    // Grammar v1, byte-exact: "ascham-detach v1\n<W>\n" — two newline-terminated lines, W a
    // non-negative decimal int64 with no sign and no leading zeros beyond "0" itself.
    const std::string::size_type nl1 = content.find('\n');
    if (nl1 == std::string::npos || content.compare(0, nl1, MAGIC_LINE) != 0) {
        malformed(path, "first line is not '" + std::string(MAGIC_LINE) + "'");
    }
    const std::string::size_type nl2 = content.find('\n', nl1 + 1);
    if (nl2 == std::string::npos) {
        malformed(path, "missing watermark line");
    }
    if (nl2 + 1 != content.size()) {
        malformed(path, "trailing bytes after the watermark line");
    }
    const std::string digits = content.substr(nl1 + 1, nl2 - nl1 - 1);
    if (digits.empty() || digits.size() > 19) {
        malformed(path, "watermark is not a decimal int64");
    }
    std::int64_t value = 0;
    for (char c : digits) {
        if (c < '0' || c > '9') {
            malformed(path, "watermark is not a decimal int64");
        }
        if (value > (std::numeric_limits<std::int64_t>::max() - (c - '0')) / 10) {
            malformed(path, "watermark overflows int64");
        }
        value = value * 10 + (c - '0');
    }
    if (digits.size() > 1 && digits[0] == '0') {
        malformed(path, "watermark has leading zeros");
    }
    watermark = value;
    return true;
}

void write_detach_watermark(const std::string& table_dir, std::int64_t watermark) {
    if (watermark < 0) {
        throw FormatError("detach watermark must be non-negative, got " + std::to_string(watermark));
    }
    const std::string final_path = detach_path(table_dir);
    const std::string tmp_path = final_path + ".tmp." + std::to_string(::getpid());
    // A stale tmp from a crashed earlier attempt (same pid reuse) would fail O_EXCL — remove it.
    if (::unlink(tmp_path.c_str()) != 0 && errno != ENOENT) {
        throw FormatError("cannot remove stale '" + tmp_path + "': " + std::strerror(errno));
    }
    int fd = ::open(tmp_path.c_str(), O_CREAT | O_EXCL | O_WRONLY, 0644);
    if (fd < 0) {
        throw FormatError("cannot create '" + tmp_path + "': " + std::strerror(errno));
    }
    const std::string content = std::string(MAGIC_LINE) + "\n" + std::to_string(watermark) + "\n";
    bool ok = true;
    std::size_t written = 0;
    while (ok && written < content.size()) {
        ssize_t n = ::write(fd, content.data() + written, content.size() - written);
        if (n < 0) {
            if (errno == EINTR) continue;
            ok = false;
        } else {
            written += static_cast<std::size_t>(n);
        }
    }
    if (ok && ::fsync(fd) != 0) {
        ok = false;
    }
    int saved_errno = errno;
    ::close(fd);
    if (!ok) {
        ::unlink(tmp_path.c_str());
        throw FormatError("cannot write '" + tmp_path + "': " + std::strerror(saved_errno));
    }
    if (::rename(tmp_path.c_str(), final_path.c_str()) != 0) {
        saved_errno = errno;
        ::unlink(tmp_path.c_str());
        throw FormatError("cannot publish '" + final_path + "': " + std::strerror(saved_errno));
    }
}

void clear_detach_watermark(const std::string& table_dir) {
    const std::string path = detach_path(table_dir);
    if (::unlink(path.c_str()) != 0 && errno != ENOENT) {
        throw FormatError("cannot remove '" + path + "': " + std::strerror(errno));
    }
}

bool segment_is_detached(const SegmentName& name, std::int64_t watermark) {
    std::int64_t id = 0;
    std::string error;
    if (!encode_segment_id(name, id, error)) {
        return false;
    }
    return id <= watermark;
}

}  // namespace arena
