#!/usr/bin/env bash
#
# scripts/db-init.sh — idempotent local bootstrap for the trips_enjoy database
# and the 20 per-service schemas.
#
# Usage:
#   bash scripts/db-init.sh [HOST:PORT] [SUPERUSER] [DBNAME]
#
# Defaults (overridable via positional args):
#   HOST:PORT = 0.0.0.0:5432
#   SUPERUSER = postgres
#   DBNAME    = trips_enjoy     (single shared database for every local service)
#
# Schemas (one per service, per docs/architecture/DATABASE_ARCHITECTURE.md:102)
# are created by per-service migrations on first boot. This script only creates
# the database; per-service V1 SQL files handle the schemas.
#
# Idempotent: re-running is a no-op when the database already exists.
#
# Extensions installed (cluster-wide, superuser-only):
#   - pg_cron   — drives partman.ensure_partitions() per
#                 docs/shared/PARTITION_FUNCTIONS.md §8.
#                 If pg_cron is not available on the cluster (e.g. vanilla
#                 Postgres without the shared_preload_libraries entry),
#                 the install is skipped and the per-service
#                 V__partition_functions.sql migration will fail loudly
#                 on first deploy. Fail-fast is intentional.

set -euo pipefail

HOST_PORT="${1:-0.0.0.0:5432}"
SUPERUSER="${2:-postgres}"
DBNAME="${3:-trips_enjoy}"

# 20 per-service schemas — exactly one per active service. Names are the
# snake_case form of the service name (DATABASE_ARCHITECTURE.md:102-112).
SCHEMAS=(
    admin
    api_gateway
    audit
    configuration
    courier
    customer
    driver
    file
    food_order
    fraud_risk
    geolocation
    identity
    ledger
    notification
    payment
    pricing
    reporting
    restaurant
    search
    trip
)

echo ">> Local Postgres bootstrap (single DB + per-service schemas)"
echo ">> host:port  = ${HOST_PORT}"
echo ">> superuser  = ${SUPERUSER}"
echo ">> database   = ${DBNAME}    (single shared DB for all 20 services)"
echo ">> schemas    = ${#SCHEMAS[@]} (one per service)"

# Probe the connection up front. A clear error here is more useful than a
# confusing "database connection failed" later.
if ! command -v createdb >/dev/null 2>&1; then
    echo "!! createdb not found on PATH. Install Postgres tooling (brew install libpq)."
    exit 1
fi

if ! psql "${HOST_PORT}" -U "${SUPERUSER}" -d postgres -tAc "SELECT 1" >/dev/null 2>&1; then
    echo "!! Cannot connect to postgres at ${HOST_PORT} as ${SUPERUSER}."
    echo "   Start your local Postgres service (e.g. brew services start postgresql@18)."
    echo "   Override defaults with: bash scripts/db-init.sh <host:port> <user> [<dbname>]"
    exit 1
fi

# Create the database if missing.
EXISTS=$(psql "${HOST_PORT}" -U "${SUPERUSER}" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${DBNAME}';" 2>/dev/null | tr -d '[:space:]')
if [[ "${EXISTS}" == "1" ]]; then
    DB_ACTION="reused"
else
    if createdb -h "${HOST_PORT%:*}" -p "${HOST_PORT##*:}" -U "${SUPERUSER}" "${DBNAME}" >/dev/null; then
        DB_ACTION="created"
    else
        echo "!! Failed to create database '${DBNAME}'."
        exit 1
    fi
fi

# Pre-create each schema with the service role allowed future DDL
# (the per-service V1 migration will re-issue CREATE SCHEMA IF NOT EXISTS so
# this is just a convenience).
SCHEMAS_READY=0
for S in "${SCHEMAS[@]}"; do
    # Skip — let the per-service Flyway/golang-migrate/alembic V1 own the
    # schema creation. This loop is a documentation no-op.
    SCHEMAS_READY=$((SCHEMAS_READY+1))
done

# Install cluster-wide extensions. These need to run as the superuser
# because Flyway runs as the application role and cannot CREATE EXTENSION.
# pg_cron drives partman.ensure_partitions() per docs/shared/PARTITION_FUNCTIONS.md
# §8; if it is missing, the per-service V__partition_functions.sql will
# try `CREATE EXTENSION IF NOT EXISTS pg_cron` and fail loudly (fail-fast
# is intentional — see PARTITION_FUNCTIONS.md §8).
echo ">> Installing cluster extensions (pg_cron)..."
if psql "${HOST_PORT}" -U "${SUPERUSER}" -d "${DBNAME}" -v ON_ERROR_STOP=0 \
        -c "CREATE EXTENSION IF NOT EXISTS pg_cron;" 2>/dev/null; then
    echo "   pg_cron: ready"
else
    echo "   pg_cron: SKIPPED (extension not available on this cluster — per-service"
    echo "             V__partition_functions.sql will fail loudly on first deploy."
    echo "             Install pg_cron in shared_preload_libraries and try again.)"
fi

# Print the JDBC URL every service will use (single DB, schema varies per service).
echo ""
echo ">> Summary"
echo ">> database : ${DB_ACTION} '${DBNAME}'  (shared by all 20 services)"

echo ""
echo ">> Per-service JDBC (dev profile): all services use one DB, separate schema."
echo "   URL pattern: jdbc:postgresql://${HOST_PORT}/${DBNAME}?currentSchema=<schema>"
echo ""
printf "   %-22s %-22s %s\n" "SERVICE" "SCHEMA" "URL"
for S in "${SCHEMAS[@]}"; do
    URL="jdbc:postgresql://${HOST_PORT}/${DBNAME}?currentSchema=${S}"
    printf "   %-22s %-22s %s\n" "${S}-service" "${S}" "${URL}"
done

echo ""
echo ">> Next steps"
echo "   1. Copy a .env.example:    cp apps/<svc>/.env.example apps/<svc>/.env"
echo "   2. Run a service:          cd apps/<svc> && ./gradlew bootRun    # Spring  (Flyway creates the schema)"
echo "                              cd apps/<svc> && go run ./cmd/server   # Go      (golang-migrate creates the schema)"
echo "                              cd apps/<svc> && python -m uvicorn app.main:app --reload  # Python (alembic creates the schema)"

echo ""
echo ">> Done."
