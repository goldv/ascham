# The reference C++ reader

The C++ reader core for the ascham segment format — `src/format/`: mmap, header/catalog decode,
layout-descriptor decode (via the checked-in flatcc bindings for `../format/Layout.fbs`), embedded
Arrow IPC schema decode (vendored nanoarrow), segment naming, and the detach sidecar. Deliberately
free of any DuckDB (or Arrow C++) dependency, so it lifts unchanged into any C++ consumer.

`test/` is the conformance runner: it exercises the reader directly against the golden corpus and
vectors under `../conformance` — the C++ half of the cross-language producer/consumer matrix, run
in this repo so a format change is validated against both languages before it ever leaves.

```
cmake -S cpp -B cpp/build && cmake --build cpp/build && ./cpp/build/ascham_conformance_test conformance
```

(`./gradlew check` runs the same via `dev/run_cpp_conformance.sh`; requires cmake and a C++20
compiler.)

## Consumers

The DuckDB extension repo (arrow_rdb) vendors byte-identical copies of `src/format/`,
`src/vendor/nanoarrow/`, and `test/` via its `scripts/sync_ascham.py`, and owns its own build
wiring plus everything DuckDB-specific (the scan layer, sqllogictests, the delta cursor). Do not
edit the vendored copies there — change here, re-sync there, one commit naming the source commit.

`src/format/layout_generated.h` is flatcc output from `../format/Layout.fbs` — regenerate with
`dev/update_flatbuffers.sh`, never edit by hand.
