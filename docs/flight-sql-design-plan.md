# Flight SQL server over the arena, powered by DuckDB — design & implementation plan

Companion to [`arena-design-plan.md`](arena-design-plan.md) and
[`segment-format.md`](segment-format.md). The arena ingest/storage layer (M0–M5) is complete; this
document designs the query tier: an **Arrow Flight SQL server whose SQL engine is embedded
DuckDB**, exposing arena tables over the network with full SQL (projections, WHERE on any column,
joins, aggregations, ORDER BY), plus a **demo writer** populating mock `quotes` and `trades`
market data and demo clients reading it back — proving the end-to-end story: live writer → shared
memory → zero-copy Arrow → DuckDB → Flight SQL → standard tooling.

Status: **design — implementation gated on the F1–F6 milestones below**, exactly as
`arena-design-plan.md` gated M0–M5.

> **Addendum (2026-07-30):** [`cold-tier-design-plan.md`](cold-tier-design-plan.md) supersedes
> parts of this design — see §12 below. In short: arena tables are served via the native `arena`
> DuckDB extension (`LOAD` + `SET arena_dir`) instead of `registerArrowStream`, restoring
> projection/filter pushdown, and each served table becomes a per-connection **union view** over
> realtime (arena) + historical (Iceberg) data around a per-table cutover watermark.

Verified up-front (Maven Central / duckdb-java source):

- `org.apache.arrow:flight-core`, `flight-sql`, `flight-sql-jdbc-driver` at **19.0.0** (matching
  the repo's arrow version); flight-core brings gRPC/netty transitively.
- `org.duckdb:duckdb_jdbc` **1.5.5.0** (bundles native libraries incl. linux x64).
- `DuckDBConnection.registerArrowStream(String name, Object arrowArrayStream)` — registers an
  `org.apache.arrow.c.ArrowArrayStream` as a queryable view (parameter typed `Object`; the driver
  reflects, avoiding a hard arrow dependency).
- `DuckDBResultSet.arrowExportStream(Object bufferAllocator, long batchSize)` — exports results as
  an `org.apache.arrow.vector.ipc.ArrowReader`.
- The arena already ships **`arrow-c-data`** — carried since M0 exactly for this integration.

---

## 1. Decisions

1. **DuckDB is the SQL engine** (embedded, in-process, `duckdb_jdbc` 1.5.5.0). Arena tables are
   registered per-query as Arrow C-data streams; DuckDB plans and executes the SQL; results export
   back as Arrow and stream over Flight. No hand-rolled SQL parser — a restricted subset would
   make the server nearly useless.
2. **Flight SQL at arrow 19.0.0**, single-node, insecure gRPC, **read-only**, no auth (stated v1
   limitations). Default port 32010.
3. **Stateless tickets**: the statement handle *is* the SQL text; DoGet executes against a fresh
   snapshot at DoGet time. No server-side registry. Documented consequence: GetFlightInfo (schema
   derivation) and DoGet run against different snapshots; within one DoGet, consistency comes from
   per-segment `Snapshot`s frozen before execution; across queries you get freshness — the live
   demo story.
4. **Per-query isolation**: each query gets a fresh in-memory DuckDB connection
   (`DuckDBConnection.newConnection("jdbc:duckdb:", ...)`) — registrations and any side effects
   die with it. A read-only statement guard rejects non-query SQL up front (belt and braces; all
   Flight `acceptPut*` mutation paths are UNIMPLEMENTED anyway).
5. **No pruning pushdown in v1**: `registerArrowStream` hands DuckDB a fixed stream — no
   projection/filter pushdown into the arena scan — so each query fully scans the referenced
   tables' segments and DuckDB does all filtering (row-exact by construction). The arena catalog's
   ts/stat min-max pruning becomes a v2 optimization (pushdown-aware scan). Honest performance
   note, acceptable at demo scale.
6. **Mock prices are Int64 implied-scale (scale 4), not Decimal128**: allocation-free `setLong`
   hot path; `px` doubles as the trades stats column; avoids Decimal128 rough edges in the Flight
   JDBC *client* driver. Scale carried as field metadata `demo.price_scale=4` (non-`arena.*` keys
   pass arena validation untouched).
7. **Two new modules**: `:flight-server` (library + server main; owns the DuckDB and Flight
   dependencies) and `:demo` (mock writer + clients). Separate demo module because the writer must
   be a *separate process* — that separation IS the arena story — and because the heavy shaded
   Flight JDBC driver and duckdb natives must not leak into the writer's classpath.

## 2. Build wiring

`settings.gradle.kts`: add `include("flight-server")` and `include("demo")`.

`gradle/libs.versions.toml` additions:

```toml
[versions]
duckdb = "1.5.5.0"
slf4j = "2.0.17"          # verify latest 2.0.x at implementation kickoff

[libraries]
arrow-flight-core = { module = "org.apache.arrow:flight-core", version.ref = "arrow" }
arrow-flight-sql = { module = "org.apache.arrow:flight-sql", version.ref = "arrow" }
arrow-flight-sql-jdbc = { module = "org.apache.arrow:flight-sql-jdbc-driver", version.ref = "arrow" }
duckdb-jdbc = { module = "org.duckdb:duckdb_jdbc", version.ref = "duckdb" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
```

`flight-server/build.gradle.kts`: `java-library`; `api(project(":arena"))`,
`api(libs.arrow.flight.sql)`, `implementation(libs.arrow.flight.core)`,
`implementation(libs.arrow.c.data)` (ArrowArrayStream export), `implementation(libs.duckdb.jdbc)`,
`runtimeOnly(libs.slf4j.simple)`; test deps as in `:arena` plus `arrow-flight-sql-jdbc` (JDBC smoke
test only). JVM flags for every test/run fork — the arena's three mandatory flags plus netty's
opt-in:

```
--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
-Dio.netty.tryReflectionSetAccessible=true
```

`JavaExec` task `runServer` (main `io.ito.flight.ArenaFlightServer`, args
`--dir <baseDir> --port <port>`). `demo/build.gradle.kts`: plain `java`;
`implementation(project(":arena"))`, `implementation(libs.arrow.flight.sql)`,
`runtimeOnly(libs.arrow.flight.sql.jdbc)`; `JavaExec` tasks `runWriter`, `runClient`,
`runJdbcClient` with the same flags.

## 3. Server classes (`io.ito.flight`)

```
ArenaFlightServer          — main(); FlightServer.builder(allocator, Location.forGrpcInsecure(...), producer)
ArenaFlightSqlProducer     — extends NoOpFlightSqlProducer
catalog/TableCatalog       — table discovery over the segment base dir
exec/ArenaTableReader      — ArrowReader over a table's frozen snapshots (the arena→DuckDB bridge)
exec/DuckDbExecutor        — per-query flow: register streams, execute, export, stream to Flight
```

- `ArenaFlightServer`: `main(String[])`; `static ArenaFlightServer start(Path baseDir, int port)`
  (port 0 = ephemeral for tests); `port()`; `awaitTermination()`; `close()`. ≈60 lines:
  `RootAllocator` + `TableCatalog` + producer + `FlightServer.builder(...).build().start()`.
- `TableCatalog`: `tables()` — subdirectories of the base dir containing `*.arena` files;
  `schema(table)` — open the newest segment's `SnapshotReader`, take `schema()`, close (one mmap +
  header parse; caching is a noted v2); `exists(table)`; `directory(table)`. **Hazard, handle
  explicitly:** `SegmentDirectory`'s constructor calls `Files.createDirectories`, so never
  construct one for an unvalidated name — check `exists()` first; unknown tables → `NOT_FOUND`
  before any `SegmentDirectory` is built.

## 4. `ArenaFlightSqlProducer` — method-by-method surface

Base class: **`org.apache.arrow.flight.sql.NoOpFlightSqlProducer`** (verify present at F1
compile) — everything defaults to `UNIMPLEMENTED`; we override only what we support; interface
defaults handle protobuf `Any` command dispatch.

| Method (arrow 19 names) | Status | Behavior |
|---|---|---|
| `getFlightInfoStatement` | **implement** | Read-only guard + table-existence check up front (fail `INVALID_ARGUMENT`/`NOT_FOUND` here, not at DoGet). Result schema via the executor's LIMIT-0 derivation (§6.4). Ticket = `Any.pack(TicketStatementQuery(statementHandle = SQL text bytes))`; one `FlightEndpoint` with an **empty locations list** ("fetch on this connection" — the single-node idiom). |
| `getStreamStatement` | **implement** | Handle → SQL → `DuckDbExecutor.execute(sql, listener)` (§6). |
| `getSchemaStatement` | **implement** | LIMIT-0 derivation → `SchemaResult(schema)`. |
| `createPreparedStatement` / `closePreparedStatement` | **implement (F5)** | Stateless: prepared handle = SQL bytes; `datasetSchema` from LIMIT-0 derivation, `parameterSchema` = empty (no `?` parameters in v1 — rejected at prepare if the SQL contains placeholders). Close = no-op success. **Required: the Flight SQL JDBC driver prepares every query.** |
| `getFlightInfoPreparedStatement` / `getStreamPreparedStatement` | **implement (F5)** | Same as the statement path via the prepared handle. |
| `acceptPutPreparedStatementQuery` | **implement (F5)** | Tolerant no-op: drain the stream, ack unchanged handle (the JDBC driver may DoPut an empty parameter bind; rejecting breaks the JDBC demo opaquely). |
| `getFlightInfoTables` / `getStreamTables` | **implement** | One row per table over `Schemas.GET_TABLES_SCHEMA` (`catalog_name`/`db_schema_name` null, `table_type="TABLE"`, IPC-serialized arena schema when `include_schema`); honor `tableNameFilterPattern` (SQL LIKE) and `tableTypes`. |
| `getFlightInfoTableTypes` / stream | **implement** | Single row `"TABLE"`. |
| `getFlightInfoCatalogs`, `getFlightInfoSchemas` / streams | **implement** | Valid empty results over the correct `GET_*` schemas; JDBC tolerates empty. |
| `getFlightInfoSqlInfo` / stream | **implement** | Via `SqlInfoBuilder`: server name `ito-arena`, engine `duckdb 1.5.5.0`, arrow 19.0.0, **read-only = true**, transactions unsupported, SQL true, substrait false. |
| `getFlightInfoTypeInfo` / stream | **implement, minimal** | Valid empty stream in v1. |
| Primary/exported/imported keys, cross-reference | inherited UNIMPLEMENTED | No key metadata in arena. |
| `acceptPutStatement`, prepared updates, bulk ingest | inherited UNIMPLEMENTED | Read-only server (also advertised via SqlInfo). |
| Substrait, transactions, savepoints, cancel, renew endpoint | inherited UNIMPLEMENTED | Clean rejection. |

## 5. SQL support

**All SQL DuckDB accepts, read-only.** Projections, WHERE on any column (`sym = 'AAPL'` included),
joins across arena tables, GROUP BY/aggregations, ORDER BY, LIMIT, CTEs, window functions — the
DuckDB dialect, verbatim. The server adds exactly one restriction, enforced by
`DuckDbExecutor.requireReadOnly(sql)`: the first keyword must be one of
`SELECT | WITH | FROM | DESCRIBE | SHOW | EXPLAIN` (DuckDB's FROM-first syntax included); anything
else → `INVALID_ARGUMENT` "read-only server". This is belt-and-braces on top of per-query
in-memory-connection isolation — a `CREATE TABLE` could only ever mutate a connection that is
discarded when the query ends — and all Flight mutation endpoints are UNIMPLEMENTED regardless.

## 6. The query path (`DuckDbExecutor`) — the correctness core

### 6.1 Per-query flow

```java
void execute(String sql, ServerStreamListener listener) {
    requireReadOnly(sql);
    List<String> tables = referencedTables(sql);          // §6.3
    try (BufferAllocator qAlloc = serverAllocator.newChildAllocator("q-" + id, 0, Long.MAX_VALUE);
         DuckDBConnection conn = DuckDBConnection.newConnection("jdbc:duckdb:", false, new Properties());
         Closeables arena = openAndRegister(conn, qAlloc, tables)) {   // §6.2/6.3
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql);
             ArrowReader result = (ArrowReader) ((DuckDBResultSet) rs).arrowExportStream(qAlloc, 4096)) {
            listener.start(result.getVectorSchemaRoot());
            while (result.loadNextBatch()) {
                listener.putNext();
            }
            listener.completed();
        }
    } catch (SQLException e) {
        listener.error(CallStatus.INVALID_ARGUMENT.withDescription(e.getMessage()).toRuntimeException());
    } // other exceptions → CallStatus.INTERNAL
}
```

The result-side streaming is simpler than a hand-built executor: `arrowExportStream` returns an
`ArrowReader` owning ONE `VectorSchemaRoot` refilled per batch — precisely flight-core's
one-root-per-stream contract (`start(root)` once, `putNext()` per `loadNextBatch()`). Result
buffers are allocated from `qAlloc` (DuckDB materializes result batches into our allocator); no
arena memory is in the result path.

**The load-bearing lifetime rule (revised for DuckDB):** DuckDB executes *lazily* — it pulls the
registered arena streams **while the result is being drained**, not at `executeQuery`. Therefore
every `SnapshotReader`, `ArenaTableReader`, and exported `ArrowArrayStream` must stay open until
after `completed()` — guaranteed structurally by the try-with-resources nesting above. Listener
zero-copy stays OFF (flight-core default): `putNext()` serializes synchronously, so even result
buffers only need to outlive each call. `qAlloc.close()` throws on leaked buffers — free leak
detection in every test.

### 6.2 `ArenaTableReader` — the arena→DuckDB bridge

```java
final class ArenaTableReader extends ArrowReader {
    ArenaTableReader(BufferAllocator alloc, Schema schema, List<Snapshot> snapshots) { ... }
    @Override public boolean loadNextBatch();   // next non-empty BatchView across snapshots, oldest-first
    @Override protected Schema readSchema();     // the arena table schema
    @Override protected void closeReadSource();  // closes the current per-batch root
}
```

Per batch: `view.root()` (zero-copy `VectorSchemaRoot` over the mmap) →
`new VectorUnloader(batchRoot).getRecordBatch()` (retain = no-op for the arena's
`ReferenceManager.NO_OP` buffers) → `VectorLoader.load` into the reader's root — **still
zero-copy**; DuckDB reads the mapped segment bytes directly through the C-data interface. Skips
zero-row batches (e.g. the trailing empty in-progress batch). The reader does NOT own the
`SnapshotReader`s — the executor does (lifetime rule above).

Export + registration, per referenced table:

```java
ArenaTableReader reader = new ArenaTableReader(qAlloc, schema, snapshots);
ArrowArrayStream stream = ArrowArrayStream.allocateNew(qAlloc);
org.apache.arrow.c.Data.exportArrayStream(qAlloc, reader, stream);
conn.registerArrowStream(tableName, stream);
```

### 6.3 Table discovery, segment iteration, snapshot consistency

- `referencedTables(sql)`: word-boundary match of each catalog table name against the SQL text —
  a conservative superset (a name inside a string literal just opens readers needlessly; harmless).
  If nothing matches, register all tables (there are only a handful). Exact identifier extraction
  is a non-goal — DuckDB itself errors cleanly on a genuinely unknown table.
- Per table: `directory.list()` once (oldest-first), then **open every `SnapshotReader` and freeze
  every `Snapshot` up-front, before execution**. Opening pins each segment's mapping (and unlinked
  inode, via the kernel refcount — arena M5 semantics) for the whole query, so retention can
  unlink mid-stream with zero effect. A raced unlink between `list()` and `open()` throws
  `UncheckedIOException` → catch and skip (it was eviction-eligible). All readers' schemas must
  equal the newest segment's, else `FAILED_PRECONDITION` "mixed schema versions".
- In-progress batches: row counts frozen at `snapshot()`; a concurrently appending writer is
  invisible (rows beyond the frozen count are not read; bytes below it are immutable — arena
  invariant 1). Serving live unsealed batches through DuckDB is safe with zero coordination.
- No reader caching in v1 (open-per-query is sub-ms on tmpfs; cache-vs-rotation invalidation is v2).

### 6.4 Result-schema derivation for GetFlightInfo / GetSchema

Flight SQL requires the result schema before DoGet. v1 derives it by executing
`SELECT * FROM (<sql>) AS q LIMIT 0` through the same register-and-export path and taking the
exported reader's schema. Honest costs, documented: the query is planned twice (once here, once at
DoGet), and LIMIT 0 over an aggregate may still scan input. The v2 optimization is
prepare-only metadata (`PreparedStatement.getMetaData()` → hand-mapped to Arrow types) — rejected
for v1 because hand-mapping DuckDB JDBC types (HUGEINT, TIMESTAMP_NS, DECIMAL, UTINYINT…) is a
correctness surface the LIMIT-0 path avoids entirely.

## 7. Demo writer (`io.ito.demo.MarketDataWriter`)

Single-threaded `main()`, one process, two `RotatingWriter`s (arena is single-writer *per table*).

Config: `--dir` (default `/dev/shm/ito` if writable, else `build/segments`), `--rate` events/sec
(default 1000), `--symbols AAPL,MSFT,GOOG,AMZN,NVDA`, `--batch-rows 4096` (small on purpose —
seals every few seconds keep sealed/unsealed behavior visible), `--max-batches 512`,
`--retention 4`.

Schemas (`DemoSchemas`, validated `ArenaSchema` factories). **Sizing note: `arena.varlen_bytes` is
the per-BATCH byte capacity for the whole column — size it as `batch_rows × max value size`:**

```
quotes: arena.table=quotes, arena.schema_version=1, arena.batch_rows=4096, arena.time_column=ts
  ts      Timestamp(ns, UTC)
  sym     Utf8   arena.varlen_bytes=32768   (4096 rows × 8 B)
  bid_px  Int64  demo.price_scale=4         (implied 1e-4 fixed point)
  ask_px  Int64  demo.price_scale=4
  bid_sz  Int32
  ask_sz  Int32
  venue   Utf8   arena.varlen_bytes=32768

trades: arena.table=trades, ..., arena.time_column=ts, arena.stats_column=px
  ts        Timestamp(ns, UTC)
  sym       Utf8   arena.varlen_bytes=32768
  px        Int64  demo.price_scale=4
  sz        Int32
  side      Utf8   arena.varlen_bytes=4096  (4096 × 1 B, "B"/"S")
  trade_id  Int64
  venue     Utf8   arena.varlen_bytes=32768
```

Generation: per-symbol mid-price random walk (steps ≈ N(0, 2 bps·mid), tick floor), 1–5 tick
spread, integer sizes, venues round-robin {XNAS, ARCA, BATS}, ~10:1 quote:trade, monotonic `ts`
from `SystemEpochNanoClock`. Symbol/venue/side bytes pre-encoded into reusable `UnsafeBuffer`s at
startup (append stays allocation-free). Epoch = `directory.latestEpoch() + 1`. Heartbeat every
~100 ms. **Append lambdas must be side-effect-free** (re-invoked on rotation): generate values
before `append`, setters only inside the lambda. Runs until SIGINT; shutdown hook closes writers.

## 8. Demo clients

- **`FlightSqlDemoClient`** (headline) — `FlightSqlClient` over `Location.forGrpcInsecure`. Now
  shows real SQL end-to-end:
  1. `getTables(...)` → print the table list.
  2. `SELECT * FROM trades ORDER BY ts DESC LIMIT 10` — latest trades (px shown ÷10⁴).
  3. `SELECT sym, count(*) AS n, avg(px)/10000.0 AS avg_px, sum(sz) AS volume
     FROM trades GROUP BY sym ORDER BY volume DESC` — aggregation.
  4. `SELECT t.sym, t.px/10000.0 AS trade_px, q.bid_px/10000.0 AS bid, q.ask_px/10000.0 AS ask
     FROM trades t ASOF JOIN quotes q ON t.sym = q.sym AND t.ts >= q.ts LIMIT 10`
     — trades joined to prevailing quotes (DuckDB ASOF JOIN; fall back to a windowed join if
     ASOF-over-arrow-streams misbehaves — verify at F4).
  5. `SELECT count(*) FROM quotes WHERE ts >= now() - INTERVAL 5 SECOND` twice, 2 s apart — the
     second count is larger: the live-freshness demo.
- **`JdbcDemoClient`** (interop proof) — `DriverManager.getConnection(
  "jdbc:arrow-flight-sql://localhost:32010/?useEncryption=false")` → plain `Statement`/`ResultSet`
  printing. This is why the prepared-statement surface (§4, F5) exists.

**Runbook:** T1 `./gradlew :demo:runWriter` · T2 `./gradlew :flight-server:runServer` ·
T3 `./gradlew :demo:runClient` (and `:demo:runJdbcClient`).

## 9. Testing

Integration tests: `FlightServer` on `localhost:0` (ephemeral; real netty path), segments in
`@TempDir`, rows written via `RotatingWriter` in-process (writer and server share only the
filesystem — as in production). F3 tests the DuckDB bridge **without Flight**: register arena
tables on a plain JDBC connection and assert SQL results directly — isolates the two integration
surfaces.

## 10. Milestones

| # | Deliverable | Exit tests |
|---|---|---|
| **F1** | Gradle wiring (settings, catalog incl. duckdb_jdbc, both build files); server boots with the NoOp-derived producer; SqlInfo | `FlightServerBootIT.clientConnectsAndReadsSqlInfo`, `FlightServerBootIT.transactionsAndUpdatesAreUnimplemented` |
| **F2** | `TableCatalog`; tables/tableTypes/catalogs/schemas metadata endpoints | `TableCatalogTest.discoversTablesUnderBaseDir`, `MetadataIT.getTablesListsWrittenTables`, `MetadataIT.getTablesIncludeSchemaRoundTrips`, `MetadataIT.tableTypesHasSingleTableRow` |
| **F3** | **The DuckDB bridge, no Flight**: `ArenaTableReader`, C-data export, `registerArrowStream`, execute + `arrowExportStream` round-trip; read-only guard; ns-timestamp fidelity | `ArenaTableReaderTest.streamsAllBatchesZeroCopy`, `DuckDbExecutorTest.selectStarValuesMatch`, `DuckDbExecutorTest.joinAndAggregateAcrossTables`, `DuckDbExecutorTest.timestampNanosSurviveRoundTrip`, `DuckDbExecutorTest.readOnlyGuardRejectsDdl` |
| **F4** | Statement endpoints wired to the executor; Flight result streaming; LIMIT-0 schema derivation | `SelectIT.selectStarStreamsAllRows`, `SelectIT.selectStarSpansRotatedSegments`, `SelectIT.projectionAndWhereAreRowExact`, `SelectIT.groupByAggregates`, `SelectIT.joinQuotesTrades`, `SelectIT.unknownTableIsNotFound`, `SelectIT.getFlightInfoSchemaMatchesDoGet` |
| **F5** | `:demo` module: `DemoSchemas`, `MarketDataWriter`, `FlightSqlDemoClient`; prepared-statement surface; `JdbcDemoClient` | `PreparedStatementIT.prepareExecuteFetch`, `JdbcSmokeIT.driverSelectsRows`, `DemoSchemasTest.schemasValidateAsArenaSchemas` |
| **F6** | Hardening: freshness, rotation-under-stream, mixed-schema rejection, large-result drain; runbook finalized | `FreshnessIT.secondQuerySeesRowsAppendedAfterFirst`, `RotationIT.streamSurvivesRetentionUnlinkMidQuery`, `ErrorsIT.mixedSchemaVersionsRejected` |

## 11. Risks

1. **Buffer lifetime vs lazy DuckDB execution** — DuckDB pulls the registered arena streams while
   the result is drained, not at `executeQuery`; every reader/stream/mapping must live until after
   `completed()`. The executor's try-with-resources nesting guarantees it structurally. Listener
   zero-copy stays OFF (result buffers, from `qAlloc`, need only outlive each synchronous
   `putNext()`).
2. **Type mapping through DuckDB's arrow scan** — the v1 profile is supported, with edges:
   `Timestamp(ns, tz)` may surface as µs `TIMESTAMPTZ` (precision loss) — pinned by
   `timestampNanosSurviveRoundTrip` at F3, with recorded options (accept + document, or cast at
   registration); unsigned ints map to DuckDB `UTINYINT..UBIGINT` (fine server-side, still awkward
   through the Flight JDBC *client* — demo schemas avoid them); `FixedSizeBinary` → `BLOB`
   (acceptable, documented).
3. **No pushdown** — full scan of referenced tables per query (fixed Arrow streams can't receive
   DuckDB filter/projection pushdown). Fine at demo scale; v2 = pushdown-aware scan or catalog
   min/max pre-filtering.
4. **LIMIT-0 schema derivation cost** — plans twice; may scan for aggregates. v2 =
   prepare-only metadata mapping.
5. **duckdb_jdbc footprint** (~50–90 MB with natives) — server-side only; the module split keeps it
   out of the demo writer's classpath.
6. **duckdb-java Arrow API stability** — `registerArrowStream`/`arrowExportStream` verified against
   current duckdb-java source (1.5.x); version pinned; both exercised immediately at F3.
7. **JDBC client driver behavior** — prepares every statement, may DoPut empty parameter binds
   (hence F5's tolerant surface).
8. **Rotation/retention racing a query** — list→open race: skip; post-open unlink: harmless
   (kernel refcount — arena M5 semantics). Open-all-then-execute ordering is load-bearing.
9. **Allocator accounting** — arena bytes flowing into DuckDB are NO_OP-managed (invisible to
   ledgers); `qAlloc` bounds and leak-checks the result path. Accepted v1.
10. **Backpressure** — `putNext()` blocks on gRPC flow control; a slow client pins that query's
    mappings and DuckDB result for the stream duration. Stream deadline is v2.

## 12. Addendum: cold-tier integration (2026-07-30)

Designed in full in [`cold-tier-design-plan.md`](cold-tier-design-plan.md); the deltas to this
plan, to be folded in when its milestones land:

- **Arena serving path**: per-query connection setup becomes `LOAD arena; SET arena_dir;` — the
  native extension replaces `registerArrowStream` for arena tables (as the extension plan's D6
  anticipated). This removes the `referencedTables(sql)` name-sniffing (§6.3) and closes Risk 3
  (no pushdown): projection/filter pushdown and zone-map pruning apply end-to-end.
- **Historical data**: connection setup additionally runs `LOAD iceberg; CREATE SECRET …;
  ATTACH … AS hist;` and, per served table, `CREATE VIEW <t> AS <hist WHERE ts < cutover UNION ALL
  arena_scan WHERE ts >= cutover>` — one logical name spans realtime + Iceberg history.
- **`CutoverTracker`** (new server singleton): background poll of `hist.ito_meta.roll_log`
  (TTL 60 s) caches the per-table cutover; per-query connections read the cache — no per-query
  catalog round-trips. Pre-attached connection pooling mitigates per-query `ATTACH` cost.
- **Cross-plan invariant**: the cutover-cache TTL must be **much smaller than** the roller's
  unlink grace period (60 s vs ≥ 15 min defaults) — see cold-tier plan §3. Violating it risks a
  stale-cutover query missing just-unlinked arena data.
