# Build for the arena reader core (src/format/) + vendored nanoarrow (src/vendor/) and the
# conformance tests. Needs only C11/C++17 compilers — builds and runs standalone (no DuckDB).
#
# The DuckDB extension proper (src/scan/) is built separately (scripts/build_extension.sh) against a
# pinned DuckDB build; this target stays independently buildable.

CXX ?= g++
CC ?= gcc
NANO_INC := src/vendor/nanoarrow/include
CXXFLAGS ?= -std=c++20 -O2 -Wall -Wextra -Wpedantic
CFLAGS ?= -std=c11 -O2
INCLUDES := -Isrc -I$(NANO_INC)
CONFORMANCE_DIR ?= ../conformance
BUILD := build

FORMAT_SRC := $(wildcard src/format/*.cpp)
VENDOR_SRC := $(wildcard src/vendor/nanoarrow/src/*.c)
TEST_SRC := $(wildcard test/cpp/*.cpp)

OBJ := $(patsubst %.cpp,$(BUILD)/%.o,$(FORMAT_SRC) $(TEST_SRC)) \
       $(patsubst %.c,$(BUILD)/%.o,$(VENDOR_SRC))

.PHONY: test clean
test: $(BUILD)/test_reader
	./$(BUILD)/test_reader $(CONFORMANCE_DIR)

$(BUILD)/test_reader: $(OBJ)
	$(CXX) $^ -o $@

$(BUILD)/%.o: %.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) $(INCLUDES) -c $< -o $@

$(BUILD)/%.o: %.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

clean:
	rm -rf $(BUILD)
