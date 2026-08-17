#!/usr/bin/env bash
#
# scripts/db-init-one.sh — create the trips_enjoy database (single shared DB).
# Per-service schemas are owned by each service's V1 migration.
#
# Usage: bash scripts/db-init-one.sh [HOST:PORT] [SUPERUSER] [DBNAME]
set -euo pipefail

HOST_PORT="${1:-0.0.0.0:5432}"
SUPERUSER="${2:-postgres}"
DB="${3:-trips_enjoy}"

if ! command -v createdb >/dev/null 2>&1; then
    echo "!! createdb not found on PATH"; exit 1
fi

EXISTS=$(psql "${HOST_PORT}" -U "${SUPERUSER}" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${DB}';" 2>/dev/null | tr -d '[:space:]' || true)
if [[ "${EXISTS}" == "1" ]]; then
    echo ">> database '${DB}' already exists — nothing to do"
    exit 0
fi

if createdb -h "${HOST_PORT%:*}" -p "${HOST_PORT##*:}" -U "${SUPERUSER}" "${DB}"; then
    echo ">> created database '${DB}'"
else
    echo "!! failed to create database '${DB}'"
    exit 1
fi
