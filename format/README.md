# The ascham format contract

This directory is the authoritative definition of the ascham segment format, split the way Apache
Arrow splits its own format definitions:

- **`segment-format.md`** — the prose spec: fixed header, batch catalog, data-region byte layout,
  alignment, type profile, metadata keys, and the concurrency protocol. These are hand-implemented
  per language (`SegmentFormat.java` here, `arena_format.hpp` in arrow_rdb) and kept honest by the
  golden corpus and conformance vectors under `conformance/`, exactly as Arrow implementations
  hand-write `ARROW1` and the alignment rules from its columnar spec.
- **`Layout.fbs`** — the Flatbuffers IDL for the layout-descriptor region (format v2+), the one
  structured, message-shaped part of the format. Language bindings are **generated at development
  time and checked in**; there is no build-time code generation.

## Regenerating the Java bindings

```
dev/update_flatbuffers.sh
```

Requires `flatc` **25.2.10** on PATH — the version must match the `flatbuffers-java` runtime pinned
in `ascham-core/build.gradle.kts` (which is also the version Arrow Java 19.0.0 ships against). The
script asserts this. Output is checked in under `ascham-core/src/generated/java/io/ascham/flatbuf/`.

## Consumers

arrow_rdb vendors a snapshot of this directory at `arrow_rdb/format/`, with its README recording
the ascham commit the snapshot came from, and generates its C bindings with flatcc 0.6.2 (matching
the flatcc runtime already vendored there via nanoarrow) — see `arrow_rdb/dev/update_flatbuffers.sh`.

A change to `Layout.fbs` beyond adding optional-with-default fields, or any change to the prose
spec's byte rules, is a format break: bump `FORMAT_VERSION`, regenerate the golden corpus and
layout vectors (`./gradlew regenerateGoldenCorpus regenerateLayoutVectors`), and re-sync arrow_rdb.
