# ascham

A shared-memory columnar ingest arena. One writer thread appends rows into an Arrow-shaped segment
in `/dev/shm`; any number of readers in any number of processes map the same file and see rows as
they are published — zero-copy, no locks, no coordination with the writer and none with each other.

The point is the seam between ingest and query. A tick-shaped feed arrives on one thread and has to
be simultaneously appendable and queryable, in-process and out, without a serialization hop or a
copy in the middle. ascham is that middle: rows land in their final Arrow memory layout once, and
every consumer reads them there.

```
   feed thread                    /dev/shm/ito/quotes/20260817.0.ascham
  ┌────────────┐   append        ┌──────────────────────────────────────┐
  │  Appender  │ ──────────────▶ │ header │ schema │ layout │ catalog │ │
  └────────────┘   release-store │                            data …    │
                                 └──────────────────────────────────────┘
                                    │ mmap          │ mmap          │ mmap
                                    ▼               ▼               ▼
                              Java reader     C++ consumer     DuckDB ext
                                                               (arrow_rdb)
```

## What it gives you

- **Zero-copy reads across process boundaries.** A reader maps the segment and hands out an Arrow
  `VectorSchemaRoot` (Java) or raw typed buffers (C++) pointing straight into the mapping.
- **A lock-free single-writer / many-reader protocol.** Publication is a release-store on a per-batch
  row count; the writer never rewinds, so a reader's view is always a valid prefix. Stale is safe;
  inconsistent is impossible. Verified by a jcstress harness, not by argument.
- **An allocation-free append path.** Steady-state zero bytes per row, asserted as a test gate.
- **A self-describing segment.** The Arrow schema and a FlatBuffers layout descriptor are embedded in
  the file, so a consumer needs the bytes and nothing else — no sidecar, no shared header, no
  dependency on the writer's code. A SHA-256 of the schema is checked at open, and a mismatch is
  fatal, because a reader misinterpreting a layout produces plausible garbage.
- **Catalog-level pruning.** One 64-byte cache-line entry per batch carries `ts_min`/`ts_max` and
  optional value-range stats, so a query skips batches before touching data.
- **Rotation on a roll cycle or on capacity**, transparent to the appender — a table is a directory
  of segments.

Deliberately not here: nested types, dictionary encoding, and a C++ writer. The
[type profile](format/segment-format.md#supported-type-profile-v1) says why.

## Documentation

| | |
|---|---|
| **[Java guide](docs/java-guide.md)** | Define a table, write rows, read them back. Start here. |
| **[C++ guide](docs/cpp-guide.md)** | Embed the read-only reference reader in a consumer. |
| **[Format contract](format/README.md)** | The authoritative definition — start page. |
| **[Segment format](format/segment-format.md)** | Byte layout, [concurrency contract](format/segment-format.md#concurrency-contract), [invariants](format/segment-format.md#invariants-correctness-core), [type profile](format/segment-format.md#supported-type-profile-v1), [metadata keys](format/segment-format.md#metadata-keys), [adding a language binding](format/segment-format.md#adding-a-language-binding), [extending the format](format/segment-format.md#extending-the-format). |
| **[`Layout.fbs`](format/Layout.fbs)** | FlatBuffers IDL for the layout-descriptor region. |
| **[Samples](ascham-samples/README.md)** | Mock market data — quotes and trades — end to end. |
| **[C++ reader](cpp/README.md)** | What lives under `cpp/`, and the vendoring rules. |

The format spec and `Layout.fbs` are jointly authoritative: no offset, field, or ordering rule
changes without a format-version bump and the procedure in the spec.

## Repository layout

| Path | What |
|---|---|
| `ascham-core/` | The Java library: schema, layout, segment, writer, reader. Plus JMH benchmarks and the jcstress ordering harness as extra source sets. |
| `cpp/` | The reference C++ reader (read-only, no Arrow C++ or DuckDB dependency) and the C++ conformance runner. |
| `format/` | The format contract: spec and FlatBuffers IDL. |
| `conformance/` | Language-neutral golden corpus, layout vectors, and type-profile matrix. |
| `ascham-samples/` | Mock market-data writer and demo drivers. |
| `dev/` | Binding regeneration and the C++ conformance script. |

Both reference implementations are validated against the same golden corpus on every `./gradlew
check`, so a format change is checked in both languages before it leaves the repo.

Querying segments through DuckDB is the job of the **arrow_rdb** extension, which lives in its own
repo and vendors `cpp/src/format/` byte-identically.

## Quick start

Java 21 and Linux (`/dev/shm`, `mmap`). Write some data and watch it land:

```sh
./gradlew :ascham-samples:runWriter                      # live feed, 1000 events/s, Ctrl-C to stop
./gradlew :ascham-samples:backfill --args="--days 3"     # three completed past days, in seconds
```

Then read it back — `SnapshotReader.open` on any file under `/dev/shm/ito/quotes/`; see the
[reader section](docs/java-guide.md#read).

Writing your own table is three steps — an Arrow schema with `ascham.*` metadata, a `RotatingWriter`,
an `Appender` — all in the [Java guide](docs/java-guide.md). **Every forked JVM needs three
`--add-exports`/`--add-opens` flags**; the [setup section](docs/java-guide.md#setup) has them and
explains the misleading failure you get without them.

## Build and test

```sh
./gradlew check          # unit + conformance tests, both languages (needs cmake and a C++20 compiler)
./gradlew :ascham-core:jcstress                                  # memory-ordering harness
./gradlew :ascham-core:jmh                                       # append/snapshot benchmarks
./gradlew :ascham-core:soakTest -Dio.ascham.soak.seconds=60      # one writer, N readers, extended
```

The C++ side builds standalone too:

```sh
cmake -S cpp -B cpp/build && cmake --build cpp/build && ./cpp/build/ascham_conformance_test conformance
```

Full task list in the [Java guide](docs/java-guide.md#gradle-tasks).
