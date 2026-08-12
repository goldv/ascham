// Compact, standard SHA-256 (FIPS 180-4). Correctness is self-checking in this project: the
// segment reader recomputes this over the embedded schema bytes and compares to the header hash,
// so a wrong implementation would fail every golden-corpus open.
#include "sha256.hpp"

#include <cstring>

namespace arena {
namespace {

inline std::uint32_t rotr(std::uint32_t x, std::uint32_t n) {
    return (x >> n) | (x << (32 - n));
}

constexpr std::uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

void process_block(std::uint32_t state[8], const std::uint8_t block[64]) {
    std::uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (std::uint32_t(block[i * 4]) << 24) | (std::uint32_t(block[i * 4 + 1]) << 16) |
               (std::uint32_t(block[i * 4 + 2]) << 8) | std::uint32_t(block[i * 4 + 3]);
    }
    for (int i = 16; i < 64; ++i) {
        std::uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
        std::uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    std::uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
    std::uint32_t e = state[4], f = state[5], g = state[6], h = state[7];
    for (int i = 0; i < 64; ++i) {
        std::uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        std::uint32_t ch = (e & f) ^ (~e & g);
        std::uint32_t t1 = h + S1 + ch + K[i] + w[i];
        std::uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        std::uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        std::uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    state[0] += a; state[1] += b; state[2] += c; state[3] += d;
    state[4] += e; state[5] += f; state[6] += g; state[7] += h;
}

}  // namespace

std::array<std::uint8_t, 32> sha256(const std::uint8_t* data, std::size_t length) {
    std::uint32_t state[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                              0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

    std::size_t full_blocks = length / 64;
    for (std::size_t i = 0; i < full_blocks; ++i) {
        process_block(state, data + i * 64);
    }

    // Final block(s) with padding: 0x80, zeros, then the 64-bit big-endian bit length.
    std::uint8_t tail[128] = {0};
    std::size_t rem = length - full_blocks * 64;
    std::memcpy(tail, data + full_blocks * 64, rem);
    tail[rem] = 0x80;
    std::size_t tail_blocks = (rem + 1 + 8 > 64) ? 2 : 1;
    std::uint64_t bits = std::uint64_t(length) * 8;
    std::size_t len_pos = tail_blocks * 64 - 8;
    for (int i = 0; i < 8; ++i) {
        tail[len_pos + i] = std::uint8_t(bits >> (56 - i * 8));
    }
    for (std::size_t i = 0; i < tail_blocks; ++i) {
        process_block(state, tail + i * 64);
    }

    std::array<std::uint8_t, 32> out{};
    for (int i = 0; i < 8; ++i) {
        out[i * 4] = std::uint8_t(state[i] >> 24);
        out[i * 4 + 1] = std::uint8_t(state[i] >> 16);
        out[i * 4 + 2] = std::uint8_t(state[i] >> 8);
        out[i * 4 + 3] = std::uint8_t(state[i]);
    }
    return out;
}

}  // namespace arena
