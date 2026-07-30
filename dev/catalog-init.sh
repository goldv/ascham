#!/bin/sh
# Bootstraps Lakekeeper and creates the `ito` warehouse backed by s3://ito-warehouse (MinIO).
# Idempotent: an already-bootstrapped server or an existing warehouse is treated as success, so
# `docker compose up` can be re-run freely. Run inside the compose network (see docker-compose.yml).
set -eu

LK="${LAKEKEEPER_URL:-http://lakekeeper:8181}"
WAREHOUSE="${WAREHOUSE_NAME:-ito}"
BUCKET="${WAREHOUSE_BUCKET:-ito-warehouse}"
S3_KEY="${S3_KEY:-minioadmin}"
S3_SECRET="${S3_SECRET:-minioadmin}"

# The S3 endpoint must be reachable from BOTH sides, because an Iceberg REST catalog *vends* this
# endpoint to clients in the LoadTable response — DuckDB uses it for data-file GET/PUT, overriding
# the endpoint in the client's own SECRET. So the internal name `minio:9000` cannot be used: it does
# not resolve on the host, and host clients would fail every write with "Could not resolve hostname".
# The docker0 gateway (172.17.0.1) is a host interface reachable from containers *and* from the host,
# and MinIO is published there on 9100 — so one value works everywhere. Override for other setups.
S3_ENDPOINT="${S3_ENDPOINT:-http://172.17.0.1:9100}"

post() {  # post <path> <json> -> prints "HTTP_CODE<newline>body"
    curl -sS -o /tmp/body -w '%{http_code}' -X POST "$LK$1" \
        -H 'Content-Type: application/json' -d "$2"
    echo
    cat /tmp/body 2>/dev/null || true
}

echo "==> bootstrapping $LK"
code="$(post /management/v1/bootstrap '{"accept-terms-of-use": true}' | head -1)"
case "$code" in
    2*) echo "    bootstrap ok ($code)" ;;
    # Already bootstrapped: Lakekeeper answers 4xx. That is the normal re-run path.
    4*) echo "    already bootstrapped ($code) — continuing" ;;
    *)  echo "    bootstrap FAILED ($code)"; cat /tmp/body; exit 1 ;;
esac

# Check for the warehouse before creating it, rather than creating and interpreting the failure.
# Lakekeeper rejects a duplicate with 400 CreateWarehouseStorageProfileOverlap ("Storage profile
# overlaps with existing warehouse"), not a 409, so error-text matching is both fragile and unclear.
echo "==> checking for existing warehouse '$WAREHOUSE'"
if curl -sS "$LK/management/v1/warehouse" | grep -q "\"name\":\"$WAREHOUSE\""; then
    echo "    warehouse '$WAREHOUSE' already exists — nothing to do"
    echo "dev catalog ready: Iceberg REST at $LK/catalog, warehouse '$WAREHOUSE'"
    exit 0
fi

echo "==> creating warehouse '$WAREHOUSE' over s3://$BUCKET"
# path-style-access: MinIO does not do virtual-host addressing by default.
# sts-enabled: the catalog vends short-lived credentials per table prefix, and DuckDB uses them.
# This is required, not cosmetic: the catalog always vends a storage config for the table's prefix,
# and DuckDB picks a secret by longest-matching scope — so the vended entry (s3://bucket/warehouse/
# <table-uuid>) always beats a client's own bucket-level SECRET. With vending disabled the vended
# entry carries no keys at all, so every data-file read/write goes out anonymous and MinIO answers
# 403 AccessDenied. Letting the catalog vend credentials also means clients need no MinIO keys.
body=$(cat <<JSON
{
  "warehouse-name": "$WAREHOUSE",
  "storage-profile": {
    "type": "s3",
    "bucket": "$BUCKET",
    "key-prefix": "warehouse",
    "endpoint": "$S3_ENDPOINT",
    "sts-endpoint": "$S3_ENDPOINT",
    "region": "local-01",
    "path-style-access": true,
    "flavor": "s3-compat",
    "sts-enabled": true,
    "remote-signing-enabled": false
  },
  "storage-credential": {
    "type": "s3",
    "credential-type": "access-key",
    "aws-access-key-id": "$S3_KEY",
    "aws-secret-access-key": "$S3_SECRET"
  },
  "delete-profile": { "type": "hard" }
}
JSON
)
out="$(post /management/v1/warehouse "$body")"
code="$(echo "$out" | head -1)"
case "$code" in
    2*) echo "    warehouse created ($code)" ;;
    # A duplicate lost a race with a concurrent init (the pre-check above handles the normal path).
    409) echo "    warehouse already exists (409) — continuing" ;;
    *)
        if echo "$out" | grep -qi 'already exists\|duplicate\|overlaps with existing'; then
            echo "    warehouse already exists ($code) — continuing"
        else
            echo "    warehouse creation FAILED ($code)"; echo "$out" | tail -n +2; exit 1
        fi
        ;;
esac

echo "==> listing warehouses"
curl -sS "$LK/management/v1/warehouse" || true
echo
echo "dev catalog ready: Iceberg REST at $LK/catalog, warehouse '$WAREHOUSE'"
