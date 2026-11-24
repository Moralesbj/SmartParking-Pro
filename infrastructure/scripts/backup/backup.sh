#!/usr/bin/env bash
# Logical backup of the compose PostgreSQL. Output: infrastructure/backups/smartparking-<UTC>.dump (custom format).
set -euo pipefail
cd "$(dirname "$0")/../../.."
mkdir -p infrastructure/backups
file="infrastructure/backups/smartparking-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose -f infrastructure/compose.yaml exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d smartparking -Fc' > "$file"
sha256sum "$file" > "$file.sha256"
echo "Respaldo creado: $file"
