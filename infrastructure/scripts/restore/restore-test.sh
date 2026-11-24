#!/usr/bin/env bash
# Restore verification WITHOUT touching the live database: restores into a throwaway database and checks Flyway history.
set -euo pipefail
file="${1:?Uso: restore-test.sh <archivo.dump>}"
cd "$(dirname "$0")/../../.."
sha256sum -c "$file.sha256"
dc="docker compose -f infrastructure/compose.yaml exec -T postgres"
$dc sh -c 'dropdb -U "$POSTGRES_USER" --if-exists restore_check && createdb -U "$POSTGRES_USER" restore_check'
$dc sh -c 'pg_restore -U "$POSTGRES_USER" -d restore_check --no-owner' < "$file"
$dc sh -c 'psql -U "$POSTGRES_USER" -d restore_check -tAc "SELECT count(*) FROM flyway_schema_history WHERE success"'
$dc sh -c 'dropdb -U "$POSTGRES_USER" restore_check'
echo "Restauración verificada en base temporal restore_check (eliminada)."
