// Minimal SHA-256, for verifying the segment header's schema hash (invariant 7). Vendored so the
// reader core has no external crypto dependency.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace arena {

std::array<std::uint8_t, 32> sha256(const std::uint8_t* data, std::size_t length);

}  // namespace arena
