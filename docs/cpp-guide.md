# Using ascham from C++

How to embed the reference C++ reader in a consumer. For the byte format, the concurrency protocol,
and how to extend either, see [`../format/segment-format.md`](../format/segment-format.md).

The C++ side is **read-only**. There is no C++ writer: segments are produced by the Java writer (see
[`java-guide.md`](java-guide.md)) and consumed here, zero-copy, out of shared memory. Everything
lives in namespace `arena`.

## How to consume it

There is **no installable library target**. `cpp/CMakeLists.txt` builds exactly one thing — the
conformance runner — because the reader core is designed to be *vendored*, not linked. That is how
the DuckDB extension repo (arrow_rdb) uses it: it copies `src/format/`, `src/vendor/nanoarrow/`, and
`test/` byte-identically via its `scripts/sync_ascham.py` and wires them into its own build.

To do the same, copy those directories and compile these sources into your target:

```cmake
add_library(ascham_reader
    src/format/detach.cpp
    src/format/layout.cpp
    src/format/mapped_file.cpp
    src/format/schema.cpp
    src/format/segment_reader.cpp
    src/format/sha256.cpp
    src/format/table_dir.cpp
    src/vendor/nanoarrow/src/flatcc.c
    src/vendor/nanoarrow/src/nanoarrow.c
    src/vendor/nanoarrow/src/nanoarrow_ipc.c)

target_include_directories(ascham_reader PUBLIC src src/vendor/nanoarrow/include)
set_target_properties(ascham_reader PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON C_STANDARD 11)

# The vendored amalgamation is third-party; do not let its warnings into your build.
set_source_files_properties(
    src/vendor/nanoarrow/src/flatcc.c
    src/vendor/nanoarrow/src/nanoarrow.c
    src/vendor/nanoarrow/src/nanoarrow_ipc.c
    PROPERTIES COMPILE_OPTIONS "-w")
```

**Dependencies: none beyond the vendored nanoarrow 0.8.0 amalgamation**, which supplies the Arrow IPC
schema decoder and the flatcc runtime the generated layout bindings compile against. No Arrow C++, no
DuckDB, no test framework. That is deliberate — the reader core has to lift unchanged into consumers
that have their own opinions about dependencies.

Requires C++20 and a C11 compiler for the vendored sources. Linux (`mmap`).

## Read a segment

```cpp
#include "format/segment_reader.hpp"

auto reader = arena::SegmentReader::open("/dev/shm/ito/quotes/20260817.0.ascham");

for (const arena::BatchInfo& batch : reader.batches()) {
    for (std::int64_t row = 0; row < batch.row_count; ++row) {
        if (!reader.is_valid(batch.index, row, /*col=*/0)) continue;
        std::int64_t ts = reader.fixed<std::int64_t>(batch.index, row, 0);
        auto [bytes, len] = reader.varlen(batch.index, row, 1);
        // ...
    }
}
```

`open` maps the file, verifies magic/version, recomputes and checks the schema SHA-256, decodes the
layout descriptor, and freezes a catalog snapshot. It throws `arena::FormatError` on anything it
cannot interpret and `std::system_error` on I/O failure. The returned reader owns the mapping.

**The snapshot is frozen at open**, matching the Java reader exactly: `active_batch_count` and every
batch's `length` are acquire-loaded once and never re-read. Rows below a batch's frozen row count are
immutable for the life of the segment, so the accessors are always consistent. To see newer rows,
open again. `heartbeat_acquire()` is the only thing that re-reads live state.

The accessors have a precondition rather than a check: `batch` indexes `batches()`, and
`row < batches()[batch].row_count`. They are:

| Accessor | For |
|---|---|
| `is_valid(batch, row, col)` | validity bitmap — check before reading a value |
| `fixed<T>(batch, row, col)` / `fixed_ptr(...)` | `PhysicalKind::FIXED` columns |
| `boolean(batch, row, col)` | `PhysicalKind::BOOL_BITMAP` columns |
| `varlen(batch, row, col)` | `PhysicalKind::VARLEN` — returns `{pointer, length}` into the mapping |

`header()` gives `SegmentHeaderInfo` (sequence, capacity, writer epoch, batch geometry, the frozen
`active_batch_count`, the schema hash, and the region table). `layout()` and `column(ordinal)` give
the per-column byte layout. `batches()[k]` also carries the zone-map stats — `ts_min`/`ts_max`,
`stat_min`/`stat_max`, `seal_nanos` — which are **meaningful only when `sealed` is true**; they are
unpublished for an in-progress batch.

Expect a trailing empty in-progress batch (`row_count == 0`): sealing opens the next batch eagerly.

## Logical types

`layout()` describes bytes, not meaning. It will tell you a column is 8-byte `FIXED`; it will not
tell you whether that is an `Int64`, a `Timestamp(ns, "UTC")`, or a `Time64`. That distinction lives
in the embedded Arrow schema, so anything that interprets values needs both:

```cpp
#include "format/schema.hpp"

auto [ipc, len] = reader.embedded_schema();
arena::TableSchema schema = arena::TableSchema::decode(ipc, static_cast<std::size_t>(len));

schema.columns[0].type;            // arena::LogicalType::TIMESTAMP
schema.columns[0].timestamp_unit;  // NANO | MICRO
schema.columns[0].timezone;        // "UTC"
schema.time_column;                // ascham.time_column
schema.stats_column;               // ascham.stats_column, empty if absent
```

`ColumnType` also carries `decimal_precision`/`decimal_scale` for `DECIMAL128` and `fixed_size` for
`FIXED_SIZE_BINARY`. `decode` throws `FormatError` on a decode failure or a type outside the v1
profile.

`time_column` and `stats_column` are what let you push a predicate down to the catalog: resolve them
to ordinals, then skip any **sealed** batch whose `ts_min`/`ts_max` or `stat_min`/`stat_max` cannot
overlap the query. Never skip an unsealed batch on stats — its stats are not published yet, so
skipping it would hide the freshest rows.

## Multi-segment tables

A table is a directory of segments. `table_dir.hpp` enumerates them oldest-first:

```cpp
#include "format/table_dir.hpp"

for (const arena::SegmentName& seg : arena::list_segments("/dev/shm/ito/quotes")) {
    auto reader = arena::SegmentReader::open(seg.path);
    // ...
}
```

Two filename forms, both UTC-interval-anchored, mirroring the Java `SegmentDirectory`:

```
<yyyyMMdd>.<seq>.ascham                      daily roll cycle
<yyyyMMdd>.<HHmm>.<minutes>m.<seq>.ascham    sub-day roll cycle
```

In-progress temp files (`*.tmp.*`) are skipped — a segment becomes visible only at its atomic rename.
An empty or missing directory returns an empty list rather than throwing.

`encode_segment_id` packs a segment's naming fields into one non-negative `int64` whose integer
ordering equals the listing order, and `decode_segment_id` is its exact inverse. Encoding
**validates**, because the filename regex admits values the packed widths cannot hold: date
1970-01-01..9999-12-31 and calendar-real, `HHmm` a real time of day, `cycle_minutes` 1..65535,
`sequence` 0..32767. Both return `false` rather than throwing; `encode_segment_id` names the
offending field in its `error` out-parameter.

A scan across several segments should require every segment to carry the same
`header().schema_sha256` — a rotation into a new schema mid-list is a hard error, not something to
paper over.

## The detach sidecar

`<table_dir>/.detach` holds one boundary id `W`; a segment is *detached* — excluded from every
directory-resolved query — iff its packed segment id is `<= W`.

```cpp
#include "format/detach.hpp"

std::int64_t watermark;
if (arena::read_detach_watermark(table_dir, watermark)) {
    // filter with arena::segment_is_detached(seg, watermark)
}
```

This is a **reader-side sidecar and not part of the byte contract**: the writer never reads or writes
it, and its name can never match the segment naming regex. Because ids are order-preserving, the
detached set is always a contiguous oldest-first prefix of the listing.

One behaviour worth knowing: a `.detach` file that exists but is malformed **throws** rather than
reading as "nothing detached". Silently re-attaching everything on a parse error is the wrong
failure. An absent file is the normal "nothing detached" state.

`write_detach_watermark` publishes atomically (write `.detach.tmp.<pid>`, fsync, `rename(2)`) — the
same idiom the writer uses for segments, so a crash leaves either the old or the new watermark.
`clear_detach_watermark` re-attaches everything; an absent file is success.

## Errors and hostile input

- `arena::FormatError` (a `std::runtime_error`, in `format/format_error.hpp` — the other headers do
  not pull it in, so include it where you catch) — the segment cannot be interpreted: bad magic,
  unsupported format version, schema-hash mismatch, malformed region, or a layout descriptor the
  flatbuffers verifier rejects.
- `std::system_error` — I/O failure opening or mapping the file.

A schema-hash mismatch is deliberately fatal. A reader misinterpreting a layout produces plausible
garbage, which is worse than a crash.

This reader maps memory another process wrote, so it treats the file as untrusted and validates
before it dereferences. If you extend it, keep that property:

- `require_in_bounds` is written in its overflow-safe form (`length > limit - offset`, never
  `offset + length > limit`).
- `validate_layout` checks every offset out of the descriptor against the batch stride before any
  accessor can use it, and varlen offsets are validated against the data buffer.
- The `catalog_offset % 8` alignment check runs *before* the first acquire load through that pointer.
- The layout descriptor goes through the generated flatcc verifier
  (`io_ascham_flatbuf_LayoutDescriptor_verify_as_root_with_identifier`) before any accessor.

`cpp/test/test_corrupt.cpp` holds 19 truncation and corruption cases; extend it alongside any change
here.

## Build and run the conformance suite

```sh
cmake -S cpp -B cpp/build && cmake --build cpp/build && ./cpp/build/ascham_conformance_test conformance
```

or, from Gradle, `./gradlew cppConformance` — which `check` depends on, so a format change is
validated against both languages before it leaves the repo. `argv[1]` is the conformance directory
and defaults to `conformance`.

No external test framework — `test/test_framework.hpp` provides `TEST`, `CHECK`, `CHECK_EQ` and
`CHECK_THROWS`, and that is the whole of it. The tests run the reader directly against the checked-in golden corpus: expected values
come from the Java golden cases, so this is genuinely the far side of a cross-language contract
rather than a self-consistency check.

## Vendoring contract

The copies in this repo are authoritative. Downstream consumers vendor them byte-identically; **do
not edit a vendored copy** — change it here, re-sync there, one commit naming the ascham commit it
came from.

`src/format/layout_generated.h` is flatcc output from `format/Layout.fbs`. Never hand-edit it;
regenerate with `dev/update_flatbuffers.sh` (see
[Regenerating the bindings](../format/segment-format.md#regenerating-the-bindings)).
