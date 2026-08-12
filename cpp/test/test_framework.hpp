// Tiny dependency-free test framework shared across the reader-core test files. Each TEST registers
// into one global registry; test_main.cpp runs them.
#pragma once

#include <functional>
#include <string>
#include <vector>

#include "format/format_error.hpp"

namespace testfw {

struct Failure {
    std::string message;
};

[[noreturn]] inline void fail(const std::string& what, const char* file, int line) {
    throw Failure{std::string(file) + ":" + std::to_string(line) + ": " + what};
}

struct TestCase {
    std::string name;
    std::function<void()> fn;
};

inline std::vector<TestCase>& registry() {
    static std::vector<TestCase> r;
    return r;
}

struct Register {
    Register(const std::string& name, std::function<void()> fn) {
        registry().push_back({name, std::move(fn)});
    }
};

// The conformance directory, set once by the runner and read by test helpers. The default is
// repo-root-relative because both `make test_conformance` and the sqllogictest runner execute from
// the repo root; the runner's argv[1] overrides it.
inline std::string& conformance_dir() {
    static std::string d = "conformance";
    return d;
}

inline std::string golden_path(const std::string& case_name) {
    return conformance_dir() + "/golden/" + case_name + ".bin";
}

}  // namespace testfw

#define CHECK(cond) \
    do { if (!(cond)) ::testfw::fail("CHECK failed: " #cond, __FILE__, __LINE__); } while (0)

#define CHECK_EQ(a, b) \
    do { if (!((a) == (b))) ::testfw::fail("CHECK_EQ failed: " #a " == " #b, __FILE__, __LINE__); } while (0)

#define CHECK_THROWS(stmt) \
    do { bool threw = false; try { stmt; } catch (const arena::FormatError&) { threw = true; } \
        if (!threw) ::testfw::fail("expected FormatError from: " #stmt, __FILE__, __LINE__); } while (0)

#define TEST(name) \
    static void name(); static ::testfw::Register reg_##name(#name, name); static void name()
