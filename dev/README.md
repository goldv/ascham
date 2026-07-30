# dev/ — local cold-tier stack

Local Iceberg REST catalog (Lakekeeper) over S3-compatible storage (MinIO) for the ito-db cold
tier. Design: [`../docs/cold-tier-design-plan.md`](../docs/cold-tier-design-plan.md).

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
cold-tier write path and pins the DDL; re-run it as a regression test.

```sh
dev/r1-spike.sh                      # generates a throwaway arena segment via :arena:runLiveWriter
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
- **Credential vending must stay on** (`sts-enabled: true`). The vended config is scoped to the
  table prefix and DuckDB picks secrets by longest-matching scope, so it always wins over your own
  `SECRET`; with vending off it carries no keys and MinIO returns 403.
- **`WITH ('format-version' = '3')` needs the quoted key** — `format_version = 3` is silently
  ignored, the table is created v2, and `TIMESTAMP_NS` columns are then rejected.
- **Partitioning is an `ALTER`**, not part of `CREATE TABLE`.
