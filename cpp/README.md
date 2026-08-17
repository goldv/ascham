# The reference C++ reader

`src/format/` is the C++ reader core for the ascham segment format: mmap, header/catalog decode,
layout-descriptor decode, embedded Arrow IPC schema decode (vendored nanoarrow), segment naming, and
the detach sidecar. Deliberately free of any DuckDB or Arrow C++ dependency, so it lifts unchanged
into any C++ consumer.

`test/` is the conformance runner — the C++ half of the cross-language producer/consumer matrix, run
in this repo so a format change is validated against both languages before it ever leaves.

```
cmake -S cpp -B cpp/build && cmake --build cpp/build && ./cpp/build/ascham_conformance_test conformance
```

(`./gradlew check` runs the same via `dev/run_cpp_conformance.sh`; requires cmake and a C++20
compiler.)

**Usage:** [`../docs/cpp-guide.md`](../docs/cpp-guide.md). **Format:**
[`../format/segment-format.md`](../format/segment-format.md).

Two rules for anyone editing here:

- The DuckDB extension repo (arrow_rdb) vendors byte-identical copies of `src/format/`,
  `src/vendor/nanoarrow/`, and `test/`. Do not edit the vendored copies there — change here, re-sync
  there, one commit naming the source commit.
- `src/format/layout_generated.h` is flatcc output from `../format/Layout.fbs`. Regenerate with
  `dev/update_flatbuffers.sh`; never edit by hand.
