# Monitoreo

- Salud: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` (sin detalles sensibles).
- ALPR: `GET /health` en ai-service (red interna).
- Logs: salida estándar de cada contenedor (`docker compose logs`). nginx propaga `X-Request-Id`.
- Pendiente: exportar métricas (Prometheus) y logs JSON con correlation id en el backend. Ver docs/operations.md.
