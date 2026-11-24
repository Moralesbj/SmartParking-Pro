# Arquitectura

## Vista general

```
navegador ──HTTPS──> nginx (frontend) ──/api──> backend Spring Boot ──JDBC──> PostgreSQL 18
                                                     │
                                                     └──HTTP + token──> ai-service (FastAPI/OpenCV)  [red interna]
```

- **Un solo origen** (nginx sirve la SPA y hace proxy de `/api`): la cookie de sesión es `SameSite=Strict` y CORS queda cerrado por defecto.
- **Monolito modular** en `backend/src/main/java/com/smartparking/modules/*`. Capas `api` (controladores/DTO), `application`
  (casos de uso transaccionales), `domain` (reglas puras: `PricingEngine`, `Plate`), `infrastructure` (adaptadores: ALPR HTTP).
  Varios módulos aún tienen la lógica en el controlador; moverla a `application` es trabajo pendiente (ver `progress.md`).
- `plate-recognition` del árbol pedido se implementa como paquete Java `platerecognition` (los identificadores Java no admiten guiones);
  `customer-portal` como `customerportal`.
- **ai-service** solo propone lecturas. El backend Java decide (`AlprService.decide`).

## Decisiones

| Decisión | Motivo |
|---|---|
| `JdbcClient` con SQL explícito en vez de JPA/Hibernate | Las reglas críticas dependen de `SELECT … FOR UPDATE`, índices parciales, `EXCLUDE USING gist` y filtros de tenant visibles en cada consulta. Ninguna entidad JPA existía; se retiró el starter para no cargar Hibernate sin uso. Flyway es la única fuente del esquema (no hay `ddl-auto`). |
| Sesión de servidor + cookie HttpOnly (no JWT) | Revocación inmediata (suspensión, cambio de contraseña), sin refresh tokens en el navegador. |
| Alcance por cabeceras `X-Organization-Id`/`X-Branch-Id` validadas en BD | El cliente solo *elige*; el rol se relee de membresías activas en cada solicitud (`ScopeService`). |
| Bloqueo por sede (`SELECT … FROM branches FOR UPDATE`) | Orden de bloqueo único para entradas, cobros, reservas y caja → sin carreras de capacidad ni de saldo. Coste: serializa operaciones de una misma sede; aceptable para el volumen de un parqueadero, revisar si una sede supera cientos de operaciones/seg. |
| Restricciones en BD como última línea | `one_open_vehicle`, `one_open_space`, exclusión GiST de reservas y turnos, FKs compuestas `(organization_id, …)` que impiden referencias entre tenants. |
| Outbox transaccional | `outbox_events` se escribe en la misma transacción; un job con `FOR UPDATE SKIP LOCKED` entrega a la bandeja interna. |
| PostgreSQL real en pruebas | Testcontainers si hay Docker; binarios embebidos (zonky) si no. Nunca H2. |

## Multi-tenancy

PostgreSQL compartido. Toda tabla de negocio tiene `organization_id` (y `branch_id` cuando aplica). Las FKs compuestas
hacen imposible, a nivel de BD, que un vehículo de A apunte a un cliente de B (probado en `TenantIsolationIT`).
`ScopedDatabase.row` siempre filtra por `organization_id` y `branch_id`.

## Eventos

`ParkingSessionOpened`, `ParkingSessionClosed`, `ReservationConfirmed`, `PaymentCaptured` se emiten al outbox.
Pendiente: `SubscriptionExpiring`, `DeviceOffline` como eventos (hoy `DEVICE_OFFLINE` se registra como alerta), SSE de ocupación.
