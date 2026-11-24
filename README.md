# SmartParking Pro

SaaS multisede para operadores de parqueaderos en Colombia (COP, `America/Bogota` por defecto, español).
Monorepositorio: `backend/` (Java 25, Spring Boot), `frontend/` (React + TypeScript), `ai-service/` (Python, ALPR),
`infrastructure/` (Docker Compose, nginx, scripts). El proyecto NetBeans original (`pom.xml`, `src/` en la raíz) se conserva sin cambios.

> **Estado:** en desarrollo. No apto para producción. Integraciones externas (DIAN, pasarela de pagos, correo/SMS/WhatsApp,
> hardware) **no están conectadas**: son simuladores explícitos o están deshabilitadas. Ver `docs/acceptance-matrix.md`.

## Versiones

| Componente | Versión |
|---|---|
| Java | 25 LTS (Temurin 25.0.5 verificado) |
| Spring Boot | 4.0.10 (Spring Security 7, Spring Framework 7, Jackson 3) |
| Flyway | 11.14.1 (gestionado por Boot) |
| PostgreSQL | 18 (Testcontainers `postgres:18-alpine` / binarios embebidos 18.7.0) |
| springdoc-openapi | 3.0.3 |
| Maven Wrapper | 3.3.4 (Maven 3.9.11) |
| Node | 22 en CI/Docker (26.13.1 verificado localmente) |
| React / Vite / TypeScript | 19.2.0 / 7.1.12 / 5.9.3 |
| Python | 3.12 · FastAPI 0.165.0 · OpenCV 5.1.0 · numpy 2.5.5 |

## Requisitos

- JDK 25. Maven no es necesario (usar `mvnw`).
- Node 22+ y npm.
- Python 3.12 para `ai-service`.
- Docker (opcional): Compose y Testcontainers. **Sin Docker** las pruebas usan un PostgreSQL real embebido (binarios zonky), nunca H2.

## Comandos

```bash
# Backend: pruebas unitarias + integración con PostgreSQL real
cd backend && ./mvnw test            # Windows: mvnw.cmd test

# Backend local sin Docker (PostgreSQL efímero; datos se pierden al salir)
cd backend && APP_ENCRYPTION_KEY=<base64-32> BOOTSTRAP_EMAIL=<correo> BOOTSTRAP_PASSWORD=<14-72 car.> \
  ./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.smartparking.LocalDevApplication

# Frontend (dev en :5173 con proxy /api → :8080)
cd frontend && npm ci && npm run lint && npm run build && npm run test
npm run dev

# ALPR
cd ai-service && python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m pytest
.venv/bin/uvicorn app.main:app --port 8000

# Stack completo (requiere Docker)
cp .env.example .env   # y genere cada secreto
docker compose --env-file .env -f infrastructure/compose.yaml up -d --build
infrastructure/scripts/healthcheck/smoke.sh http://localhost:8088
```

## Puertos

| Servicio | Puerto |
|---|---|
| frontend (nginx, sirve SPA y proxy `/api`) | `HTTP_PORT` (8088) |
| backend | 8080 (red interna en Compose) |
| ai-service | 8000 (red interna) |
| postgres | 5432 (red interna) |

OpenAPI: `/api/v1/openapi` · Swagger UI: `/api/v1/docs` (desactivable con `API_DOCS_ENABLED=false`).

## Configuración (`.env`)

Ver `.env.example`. Secretos solo por variables de entorno: `DB_PASSWORD`, `APP_ENCRYPTION_KEY` (AES-256, base64 de 32 bytes),
`ALPR_SERVICE_TOKEN`, `BOOTSTRAP_PASSWORD`. Ninguno tiene valor por defecto ni está en el repositorio.

## Primer ingreso

No hay datos ni credenciales demo precargados. Con la tabla `users` vacía, `BOOTSTRAP_EMAIL`/`BOOTSTRAP_PASSWORD`
crean un superadministrador, una organización y una sede. Se ingresa con correo y contraseña: la verificación en dos
pasos está desactivada por defecto (`MFA_ENABLED=false`). Luego cree zonas, cupos, tarifas y usuarios desde la interfaz y
retire `BOOTSTRAP_PASSWORD`.

## Migraciones

Flyway en `backend/src/main/resources/db/migration` (`V1__foundation`, `V2__auth_hardening`, `V3__alpr_and_device_commands`).
Se aplican al arrancar; nunca editar una migración ya aplicada.

## Problemas frecuentes

- **No puedo iniciar sesión en local por HTTP:** use `COOKIE_SECURE=false` (solo local).
- **403 `MFA_ENROLLMENT_REQUIRED`:** solo ocurre con `MFA_ENABLED=true` y `REQUIRE_MFA_PRIVILEGED=true`; active la verificación en *Seguridad* (requiere `APP_ENCRYPTION_KEY`).
- **El enlace de recuperación no llega:** `MAIL_MODE=DISABLED` no envía correos. En una máquina de desarrollo `MAIL_MODE=LOG` lo escribe en el log del backend.
- **VS Code marca errores en `backend/`:** el IDE resuelve el `pom.xml` raíz de NetBeans. Importe `backend/pom.xml` como proyecto Maven; la compilación válida es `./mvnw`.

Documentación: `docs/` (arquitectura, seguridad, reglas de negocio, API, despliegue, operación, progreso y matriz de aceptación).
