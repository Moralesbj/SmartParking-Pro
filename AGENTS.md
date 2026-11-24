# SmartParking Pro

Implement the attached master specification incrementally. Preserve the original NetBeans starter under src/ and pom.xml. The web application lives in backend/, frontend/, ai-service/, infrastructure/.

- Java 25, Spring Boot, PostgreSQL/Flyway; React/TypeScript; Python for ALPR only.
- Enforce organization and branch membership server-side. Never trust scope or permissions supplied by clients.
- Money uses BigDecimal; timestamps use Instant; business time uses branch ZoneId.
- Lock capacity and financial aggregates transactionally. Enforce database uniqueness and reservation exclusions.
- HttpOnly sessions, CSRF, password hashing, no committed secrets, no sensitive audit payloads.
- Integrations default to disabled or explicitly simulated. Never label an internal receipt as a DIAN invoice.
- Every change needs appropriate tests. A build blocked by dependency access is not a passed build.
- Keep docs/progress.md and docs/acceptance-matrix.md honest, with commands, results and remaining work.
- Do not publish or delete existing data without authorization.
