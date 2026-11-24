# Implementation plan

1. Foundation: preserve NetBeans, establish Java 25 backend, Flyway, security, Docker, CI, shell UI.
2. M01–M06: authentication, RBAC/membership, organizations, customers, vehicles, capacity.
3. M07–M09: parking, reservations, deterministic tariffs and concurrency.
4. M10–M13: subscriptions, financial ledger, cash and workforce.
5. M14–M16: explicit ALPR/device simulators, notification outbox.
6. M17–M20: scoped reports, immutable audit, own-data portal, settings.
7. Integration: PostgreSQL concurrency/tenant tests, frontend tests, E2E, accessibility.
8. Operational documentation and honest acceptance matrix.

No provider credentials are needed to develop; real integrations remain disabled. No production deployment is authorized. Compilation and integration checks require dependency access; Docker is not installed in the current execution environment.
