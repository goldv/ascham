# Build for the arena reader core (src/format/) and its conformance tests. This target has NO
# DuckDB dependency and needs only a C++20 compiler — it builds and runs standalone.
#
# The DuckDB extension proper (src/scan/, the arena_scan table function) is built separately via
# CMake against a pinned DuckDB submodule; see README.md. That build is intentionally not wired
# here so the reader core stays independently buildable.

CXX ?= g++
CXXFLAGS ?= -std=c++20 -O2 -Wall -Wextra -Wpedantic
CONFORMANCE_DIR ?= ../conformance

FORMAT_SRC := $(wildcard src/format/*.cpp)
TEST_SRC := $(wildcard test/cpp/*.cpp)
BUILD := build

.PHONY: test clean
test: $(BUILD)/test_reader
	./$(BUILD)/test_reader $(CONFORMANCE_DIR)

$(BUILD)/test_reader: $(FORMAT_SRC) $(TEST_SRC)
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) -Isrc $(FORMAT_SRC) $(TEST_SRC) -o $@

clean:
	rm -rf $(BUILD)
