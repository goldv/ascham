// The corpus gate: every golden segment must hash to the SHA-256 recorded in conformance/manifest.json.
//
// The corpus is a vendored copy of ascham/conformance — the producer of the format is in another
// repo, so nothing in this repo can regenerate it. This test is what makes that copy trustworthy: a
// partial sync, a hand-edited .bin, or a corrupted checkout fails loudly here instead of silently
// changing what "conformance" means. test/sql/corpus_gate.test asserts the same thing through SQL so
// the gate also runs in `make test`; both read the same manifest.
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "format/sha256.hpp"
#include "test_framework.hpp"

namespace {

std::vector<std::uint8_t> read_file(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        ::testfw::fail("cannot open " + path, __FILE__, __LINE__);
    }
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

std::string hex(const std::array<std::uint8_t, 32> &digest) {
    static const char *digits = "0123456789abcdef";
    std::string s;
    s.reserve(64);
    for (std::uint8_t b : digest) {
        s.push_back(digits[b >> 4]);
        s.push_back(digits[b & 0xf]);
    }
    return s;
}

struct ManifestCase {
    std::string name;
    std::string sha256;
    std::int64_t bytes = 0;
};

// Extracts the string value of "key":"..." starting at or after `from`. The manifest is generated,
// single-line-per-case JSON with no escapes or nesting inside a case, so a scan is enough and keeps
// the reader core's zero-dependency rule intact.
bool field(const std::string &s, std::size_t from, const std::string &key, std::string &out) {
    std::size_t k = s.find("\"" + key + "\":", from);
    if (k == std::string::npos) return false;
    std::size_t q = s.find('"', k + key.size() + 3);
    if (q == std::string::npos) return false;
    std::size_t end = s.find('"', q + 1);
    if (end == std::string::npos) return false;
    out = s.substr(q + 1, end - q - 1);
    return true;
}

bool int_field(const std::string &s, std::size_t from, const std::string &key, std::int64_t &out) {
    std::size_t k = s.find("\"" + key + "\":", from);
    if (k == std::string::npos) return false;
    out = std::strtoll(s.c_str() + k + key.size() + 3, nullptr, 10);
    return true;
}

std::vector<ManifestCase> parse_manifest(const std::string &path) {
    auto bytes = read_file(path);
    std::string s(bytes.begin(), bytes.end());
    std::vector<ManifestCase> cases;
    std::size_t pos = 0;
    while (true) {
        std::size_t k = s.find("\"name\":", pos);
        if (k == std::string::npos) break;
        ManifestCase c;
        if (!field(s, k, "name", c.name)) break;
        if (!field(s, k, "segmentSha256", c.sha256)) {
            ::testfw::fail("manifest case '" + c.name + "' has no segmentSha256", __FILE__, __LINE__);
        }
        int_field(s, k, "segmentBytes", c.bytes);
        cases.push_back(c);
        pos = k + 7;
    }
    return cases;
}

}  // namespace

TEST(golden_corpus_matches_manifest_hashes) {
    auto cases = parse_manifest(::testfw::conformance_dir() + "/manifest.json");
    CHECK(cases.size() >= 8);

    for (const auto &c : cases) {
        auto bytes = read_file(::testfw::golden_path(c.name));
        if (c.bytes != 0 && static_cast<std::int64_t>(bytes.size()) != c.bytes) {
            ::testfw::fail("golden '" + c.name + "' is " + std::to_string(bytes.size()) +
                               " bytes, manifest says " + std::to_string(c.bytes),
                           __FILE__, __LINE__);
        }
        std::string actual = hex(arena::sha256(bytes.data(), bytes.size()));
        if (actual != c.sha256) {
            ::testfw::fail("golden '" + c.name + "' hashes to " + actual + ", manifest says " + c.sha256 +
                               " — the vendored corpus does not match its manifest",
                           __FILE__, __LINE__);
        }
    }
}
