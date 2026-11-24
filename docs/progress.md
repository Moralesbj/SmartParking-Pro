# Progreso

## Publicación — 2026-11-24
Repositorio público https://github.com/Moralesbj/SmartParking-Pro (rama `main`). Primer CI en GitHub: corregidos permisos de
ejecución de `mvnw`/scripts, `trivy-action` fijado por commit (v0.36.0, posterior al ataque de marzo de 2026), `.gitleaks.toml`
(excepción solo para credenciales inventadas de pruebas) y dependencias del frontend sin vulnerabilidades conocidas
(react-router-dom 7.18.5, zod 4.6.7, vite 7.3.7, vitest 4.1.12; `npm run test` usa `scripts/vitest.mjs` por un fallo de Vitest 4
en Windows con la unidad en minúscula). Resultado: `CI` y `Security` en verde.

## Cambio posterior — 2026-11-23: verificación en dos pasos desactivada

A pedido del propietario: `app.security.mfa-enabled` (`MFA_ENABLED`, por defecto `false`) apaga el segundo factor. El ingreso
pide solo correo y contraseña, los endpoints de MFA responden 404 `MFA_DISABLED` y la exigencia para administradores no aplica.
El código se conserva y vuelve con `MFA_ENABLED=true` (`AuthIT` lo prueba así). Contraseñas: mínimo 8 caracteres (antes 14).
Resultados: `./mvnw test` 112/112 (incluye `MfaDisabledIT`), frontend 18/18 y build OK, siembra 113/113,
Playwright 6/6 en Edge. La base local tiene exactamente 5 cuentas, una por rol.

## Última sesión — 2026-11-23 (categorías, cobro por minuto en bloques de 12 h, mensualidades por categoría)

Verificado en Windows 11, JDK 25.0.5, Node 26.13.1, sin Docker (PostgreSQL 18 embebido real).

| Comando | Resultado |
|---|---|
| `cd backend && ./mvnw test` | 109 pruebas, 0 fallos (incluye `ParkingRateServiceTest` 40, `CoverageWindowsTest` 5, `VehicleBillingIT` 9, `MigrationV5IT` 2, concurrencia 9) |
| `cd frontend && npm run lint && npm run build && npm run test` | type-check OK, build OK, 18 pruebas OK |
| `python infrastructure/scripts/seed/seed_local.py http://localhost:5173 <cred.json>` | 111/111 verificaciones por API contra la app en vivo |
| `E2E_CHANNEL=msedge SP_CREDENTIALS=<cred.json> npx playwright test` | 6/6 (5 roles por todas las pantallas + flujo de caja: entrada sin cupo, QR, liquidación, cobro, salida) |
| `cd ai-service && python -m pytest` | no se tocó en esta sesión (24 pruebas en la sesión anterior) |
| GitHub Actions `CI` y `Security` (commit 624dc05) | OK: backend con Testcontainers (PostgreSQL real), frontend, ai-service, construcción de las 3 imágenes Docker, Trivy (crítico), npm audit, gitleaks |
| `docker compose … up` | **No ejecutado** (sin Docker en el equipo local) |

Cambios: migración V5 (4 categorías, `vehicle_rates` + plantilla + versiones, snapshot por estancia, código de tiquete, descuentos,
planes por categoría con horario/gracia/sedes), `ParkingRateService`, consulta de tiquete, pantallas "Tarifas por vehículo" y
"Consultar tiquete". Se retiraron del cálculo y de la interfaz las franjas nocturna/fin de semana/festivo y el modelo por hora:
la especificación define una sola tarifa por minuto por categoría y prohíbe reinicios a medianoche. Sus tablas se conservan.

## Hecho
- Base: Spring Boot 4.0.10, Maven Wrapper, Flyway V1–V4, PostgreSQL real en pruebas, Dockerfiles, Compose, nginx, CI.
- M01: MFA TOTP obligatorio para administradores, recuperación de contraseña, revocación de sesiones, registro de accesos, rate limit.
- M06: `/spaces/availability`, `/spaces/map` + mapa visual.
- M07/M08/M11/M12: flujo A, idempotencia y concurrencia probados.
- M09: tarifa por minuto con tope de tarifa plena por bloque, configurable por sede y categoría, versionada; simulador `/pricing/quote`.
- M07: código de 6 dígitos y QR por tiquete, consulta por placa/código/QR con liquidación completa, descuentos supervisados.
- M10: crear, pagar→activar, renovar (una por contrato), suspender/reactivar, no solapamiento (GiST), aviso de vencimiento único.
- M13: turnos sin solapamiento, ventana de inicio, cancelación supervisada, novedades, operador ve solo sus turnos.
- M14/M15: ai-service OpenCV (solo imágenes sintéticas), decisión en Java, barrera simulada con autorización y timeout.
- M16: despachador outbox por evento/transacción, sin duplicados, preferencias; canales externos no configurados.

## Pendiente (siguiente sesión, en este orden)
1. M02 RBAC granular (`roles`/`permissions`), invitaciones, multi-sede desde UI.
2. M08 penalizaciones, `reservation_events`; flujo B completo. Ventana de salida tras el pago (no definida en la especificación).
3. M17 dashboard por rol, XLSX/PDF, consolidado multisede · M18 valores anteriores/nuevos redactados en auditoría.
4. M19 registro/invitación de clientes · M20 numeración de tickets, branding.
5. M04 historial/retención · M05 otros países · `docs/acceptance-matrix.md` aún no redactado.
6. Ejecutar Compose donde haya Docker; integrar el E2E de Playwright (ya existe, local) en CI con una pila sembrada.
7. Proveedores (DIAN, pasarela, correo/SMS): bloqueados hasta tener cuentas y credenciales.

Siguiente comando: `cd backend && ./mvnw test`.
