cmake_minimum_required(VERSION 3.16)

# The reference C++ reader for the ascham segment format, plus its conformance runner.
#
# This is the authoritative home of the reader core (src/format/) — the DuckDB extension repo
# (arrow_rdb) vendors byte-identical copies of src/format/, src/vendor/nanoarrow/, and test/ via
# its scripts/sync_ascham.py and wires them into its own build. No DuckDB dependency here; the
# runner exercises the reader directly against the golden corpus at ../conformance.
project(ascham_cpp CXX C)

# Vendored nanoarrow 0.8.0 amalgamation (IPC schema decoding + the flatcc runtime the generated
# layout bindings compile against). See docs in arrow_rdb/docs/known-issues.md for why vendored.
set(NANOARROW_SOURCES
    src/vendor/nanoarrow/src/flatcc.c
    src/vendor/nanoarrow/src/nanoarrow.c
    src/vendor/nanoarrow/src/nanoarrow_ipc.c)
set_source_files_properties(${NANOARROW_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")

add_executable(ascham_conformance_test
    test/test_main.cpp
    test/test_schema.cpp
    test/test_corrupt.cpp
    test/test_manifest_gate.cpp
    test/test_segment_id.cpp
    test/test_detach.cpp
    test/test_layout_vectors.cpp
    test/test_type_profile_vectors.cpp
    src/format/detach.cpp
    src/format/layout.cpp
    src/format/mapped_file.cpp
    src/format/schema.cpp
    src/format/segment_reader.cpp
    src/format/sha256.cpp
    src/format/table_dir.cpp
    ${NANOARROW_SOURCES})
target_include_directories(ascham_conformance_test PRIVATE src src/vendor/nanoarrow/include test)
set_target_properties(ascham_conformance_test PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    C_STANDARD 11)
