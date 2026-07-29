// Thrown when a segment cannot be interpreted: bad magic, unsupported format version, a schema-hash
// mismatch (invariant 7 — a hard failure, never silent misinterpretation), or a malformed region.
#pragma once

#include <stdexcept>
#include <string>

namespace arena {

struct FormatError : std::runtime_error {
    explicit FormatError(const std::string& message) : std::runtime_error(message) {}
};

}  // namespace arena
