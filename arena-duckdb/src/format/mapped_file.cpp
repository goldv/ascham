#include "mapped_file.hpp"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <stdexcept>
#include <system_error>
#include <utility>

namespace arena {

MappedFile MappedFile::open(const std::string& path) {
    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        throw std::system_error(errno, std::generic_category(), "open " + path);
    }
    struct stat st{};
    if (::fstat(fd, &st) != 0) {
        int e = errno;
        ::close(fd);
        throw std::system_error(e, std::generic_category(), "fstat " + path);
    }
    std::size_t size = static_cast<std::size_t>(st.st_size);
    void* addr = ::mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
    ::close(fd);  // the mapping keeps the inode alive; the fd is no longer needed
    if (addr == MAP_FAILED) {
        throw std::system_error(errno, std::generic_category(), "mmap " + path);
    }
    MappedFile m;
    m.data_ = static_cast<const std::uint8_t*>(addr);
    m.size_ = size;
    return m;
}

MappedFile::~MappedFile() {
    if (data_ != nullptr) {
        ::munmap(const_cast<std::uint8_t*>(data_), size_);
    }
}

MappedFile::MappedFile(MappedFile&& other) noexcept : data_(other.data_), size_(other.size_) {
    other.data_ = nullptr;
    other.size_ = 0;
}

MappedFile& MappedFile::operator=(MappedFile&& other) noexcept {
    if (this != &other) {
        if (data_ != nullptr) {
            ::munmap(const_cast<std::uint8_t*>(data_), size_);
        }
        data_ = other.data_;
        size_ = other.size_;
        other.data_ = nullptr;
        other.size_ = 0;
    }
    return *this;
}

}  // namespace arena
