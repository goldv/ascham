// Read-only shared memory mapping of a segment file. MAP_SHARED so the mapping tracks the writer's
// appends; held for the whole query so a concurrent retention unlink is harmless (the kernel
// refcount keeps the inode alive — segment-format.md M5 reclamation semantics).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace arena {

class MappedFile {
public:
    static MappedFile open(const std::string& path);

    MappedFile() = default;
    ~MappedFile();
    MappedFile(MappedFile&& other) noexcept;
    MappedFile& operator=(MappedFile&& other) noexcept;
    MappedFile(const MappedFile&) = delete;
    MappedFile& operator=(const MappedFile&) = delete;

    const std::uint8_t* data() const { return data_; }
    std::size_t size() const { return size_; }

private:
    const std::uint8_t* data_ = nullptr;
    std::size_t size_ = 0;
};

}  // namespace arena
