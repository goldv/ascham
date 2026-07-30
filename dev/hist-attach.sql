-- Attaches the local dev Iceberg catalog (see dev/docker-compose.yml) as `hist`.
--   duckdb -unsigned -init dev/hist-attach.sql
-- or from any DuckDB session:  .read dev/hist-attach.sql
--
-- Notes on the exact incantation (each clause is load-bearing, verified against DuckDB v1.5.5):
--  * httpfs must be loaded before the S3 secret — the `S3` secret type lives in httpfs, not iceberg.
--  * AUTHORIZATION_TYPE 'none' is required: ATTACH defaults to oauth2 and fails with
--    "AUTHORIZATION_TYPE is 'oauth2', yet no 'secret' was provided" against the no-auth dev catalog.
--  * The ENDPOINT below (172.17.0.1:9100, the docker0 gateway) must match the warehouse's storage
--    profile endpoint, because the catalog vends its endpoint to clients for data-file I/O.
INSTALL iceberg;
INSTALL httpfs;
LOAD httpfs;
LOAD iceberg;

CREATE OR REPLACE SECRET minio (
    TYPE S3,
    KEY_ID 'minioadmin',
    SECRET 'minioadmin',
    ENDPOINT '172.17.0.1:9100',
    URL_STYLE 'path',
    USE_SSL false
);

ATTACH IF NOT EXISTS 'ito' AS hist (
    TYPE iceberg,
    ENDPOINT 'http://localhost:8181/catalog',
    AUTHORIZATION_TYPE 'none'
);

CREATE SCHEMA IF NOT EXISTS hist.ito;
