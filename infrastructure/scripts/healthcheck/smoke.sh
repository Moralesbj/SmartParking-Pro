#!/usr/bin/env bash
# Smoke test against a running stack: health, CSRF bootstrap, anonymous rejection.
set -euo pipefail
base="${1:-http://localhost:8088}"
curl -fsS "$base/actuator/health" | grep -q '"UP"' && echo "health OK"
curl -fsS "$base/api/v1/auth/csrf" -c /tmp/sp-cookies >/dev/null && echo "csrf OK"
code=$(curl -s -o /dev/null -w '%{http_code}' "$base/api/v1/auth/me")
[ "$code" = "401" ] && echo "anonymous /me -> 401 OK" || { echo "esperado 401, obtenido $code"; exit 1; }
curl -fsS "$base/" | grep -q '<div id="root">' && echo "frontend OK"
