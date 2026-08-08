# dev/ — local cold-tier stack

Local Iceberg REST catalog (Lakekeeper) over S3-compatible storage (MinIO) for the ascham cold
tier. Design: [`../docs/cold-tier-design-plan.md`](../docs/cold-tier-design-plan.md).

Since R5.5 the roller itself is pure Java (native Iceberg API) and **needs neither DuckDB nor the
arena extension** — it can even roll to a plain local directory with no stack at all
(`./gradlew :ascham-archive:archive --args="roll --arena-dir /dev/shm/ito --dest build/warehouse"`).
This stack is the REST/S3 integration target (`./gradlew :ascham-archive:rollIT`)
and the DuckDB *query* surface over what the roller writes.

## Start / stop

```sh
docker compose -f dev/docker-compose.yml up -d      # idempotent; re-runnable
docker compose -f dev/docker-compose.yml down -v     # stop and wipe the warehouse
```

Host ports (deliberately non-default so this coexists with other local stacks):

| Port | Service |
|---|---|
| 8181 | Lakekeeper — Iceberg REST at `/catalog`, management API at `/management/v1` |
| 9100 | MinIO S3 API |
| 9101 | MinIO console (`minioadmin` / `minioadmin`) |

Postgres is Lakekeeper's private metadata store and is not published. `up` also runs two idempotent
one-shot jobs: `minio-init` (creates bucket `ito-warehouse`) and `catalog-init` (bootstraps the
server, creates warehouse `ito`).

## Query it

```sh
# Attach the catalog as `hist` in a DuckDB session:
duckdb -unsigned -init dev/hist-attach.sql

# With the arena extension too (realtime + historical in one session):
duckdb -unsigned -init dev/hist-attach.sql \
  -c "LOAD '$(pwd)/arena-duckdb/build/arena.duckdb_extension'; SHOW ALL TABLES;"
```

## R1 write-path spike

`dev/r1-spike.sh` rolls a real arena segment into a day-partitioned, format-version-3 Iceberg table
— in one transaction with its `roll_log` watermark row — and asserts the outcome. It proved the
original DuckDB write path and pins that DDL. Historical since R5.5 (the roller no longer writes
through DuckDB), but still a useful regression test of the *DuckDB side*: extension coexistence,
v3 DDL, and ns round-tripping in the query engine.

```sh
dev/r1-spike.sh                      # generates a throwaway arena segment via :ascham-core:runLiveWriter
dev/r1-spike.sh /dev/shm/ito/quotes  # or roll an existing arena table dir
```

Requires the dev stack up and the arena extension built
(`DUCKDB=/path/to/duckdb arena-duckdb/scripts/build_extension.sh`).

## Gotchas worth knowing before you change anything here

These each cost a debugging round; the design doc §9 explains them in full.

- **`LOAD httpfs` before `CREATE SECRET (TYPE S3, …)`** — the S3 secret type ships in httpfs, not
  iceberg.
- **`AUTHORIZATION_TYPE 'none'` on `ATTACH`** — it defaults to `oauth2` against this no-auth catalog.
- **The warehouse S3 endpoint must resolve from the host *and* from containers.** The catalog vends
  its storage endpoint to clients, so `http://minio:9000` breaks every host-side write with
  "Could not resolve hostname". We use the docker0 gateway `http://172.17.0.1:9100` (a host
  interface that containers can also reach). Override with `ITO_S3_ENDPOINT`.
- **The catalog's advertised base URI has the same reachability constraint.** `/v1/config` returns
  `overrides.uri`, and the Iceberg *Java* REST client switches to it for every subsequent request
  (DuckDB's `ATTACH` ignores it, which is why only the native roller ever tripped on this). So
  `LAKEKEEPER__BASE_URI` is the docker0 gateway `http://172.17.0.1:8181`, not `http://lakekeeper:8181`.
  Override with `ITO_CATALOG_URI`.
- **Credential vending must stay on** (`sts-enabled: true`). The vended config is scoped to the
  table prefix and DuckDB picks secrets by longest-matching scope, so it always wins over your own
  `SECRET`; with vending off it carries no keys and MinIO returns 403.
- **`WITH ('format-version' = '3')` needs the quoted key** — `format_version = 3` is silently
  ignored, the table is created v2, and `TIMESTAMP_NS` columns are then rejected.
- **Partitioning is an `ALTER`**, not part of `CREATE TABLE`.
