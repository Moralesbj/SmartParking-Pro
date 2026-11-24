# Seguridad

## Implementado y probado

| Control | Dónde | Prueba |
|---|---|---|
| Contraseñas BCrypt (coste 12), 8–72 caracteres y al menos 6 caracteres distintos | `SecurityConfig`, `AuthService.validatePassword` | `AuthIT` |
| Sesión de servidor, cookie `SPSESSION` HttpOnly, `SameSite=Strict`, `Secure` (configurable solo para local) | `application.yml` | smoke en vivo |
| Nueva sesión al ingresar y tras MFA (anti-fijación) | `AuthController` | `AuthIT` |
| CSRF (cookie `XSRF-TOKEN` + cabecera `X-XSRF-TOKEN`) | `SecurityConfig` | `TenantIsolationIT.loginWithoutCsrfIsRejected` |
| Bloqueo 15 min tras 5 fallos (persistente en BD) + límite por IP | `AuthService`, `RateLimiter` | `TenantIsolationIT` |
| MFA TOTP (RFC 6238) **desactivado por defecto** a pedido del propietario (`MFA_ENABLED=false`: solo correo y contraseña, endpoints de MFA → 404). Con `MFA_ENABLED=true` vuelve: obligatorio para ADMIN/SUPERADMIN, anti-replay por paso, 8 códigos de recuperación de un uso (hash SHA-256) | `AuthService`, `Totp`, `ScopeService` | `MfaDisabledIT`, `AuthIT`, `TotpTest` |
| Semilla TOTP cifrada AES-256-GCM con `APP_ENCRYPTION_KEY` | `SecretCipher` | `AuthIT`, `TotpTest` |
| Recuperación de contraseña: token aleatorio de 256 bits, solo hash en BD, 30 min, un uso, respuesta idéntica exista o no la cuenta | `AuthService` | `AuthIT` |
| Revocación de sesiones al cambiar contraseña o desactivar usuario | `SessionValidityFilter` (`credentials_changed_at` vs. hora de autenticación del reloj de BD) | `AuthIT` |
| Suspensión de membresía con efecto inmediato | `ScopeService` | `TenantIsolationIT` |
| Aislamiento entre organizaciones y sedes | `ScopeService`, FKs compuestas | `TenantIsolationIT` |
| Registro de accesos inmutable (sin contraseñas, tokens ni correos de cuentas inexistentes) | `access_events` + trigger | `AuthIT` |
| Auditoría inmutable (trigger que rechaza UPDATE/DELETE) | `audit_logs` | `TenantIsolationIT.auditLogsCannotBeModified` |
| Errores RFC 9457 sin stack traces ni SQL, UTF-8 | `ApiErrors`, `SecurityConfig` | `AuthIT`, `TenantIsolationIT` |
| CSP, `X-Frame-Options: DENY`, HSTS, `Referrer-Policy` | `SecurityConfig`, nginx | smoke en vivo |
| CORS cerrado por defecto; solo orígenes exactos, sin comodines | `SecurityConfig` | — |
| Validación de imágenes ALPR por número mágico, tamaño y píxeles; token de servicio | `ai-service/app/api/routes.py` | `test_alpr.py` |

## Limitaciones conocidas (antes de producción)

- `RateLimiter` es en memoria por instancia. Con varias réplicas debe pasar a un almacén compartido. El bloqueo por cuenta sí es persistente.
- El envío de correo está deshabilitado: la recuperación de contraseña genera el token pero no lo entrega hasta configurar un proveedor.
- No hay rotación de `APP_ENCRYPTION_KEY` (requiere re-cifrado versionado; el formato `v1:` lo prepara).
- Auditoría: inmutable frente a la aplicación; un superusuario de PostgreSQL aún puede desactivar el trigger. Para no repudio fuerte se requiere exportación a almacenamiento WORM.
- `audit_logs` aún no guarda valores anteriores/nuevos redactados ni IP.
- Faltan escaneo antivirus de cargas y análisis SAST en CI (hay Trivy, `npm audit` y gitleaks, no ejecutados aún en GitHub).

## Privacidad (Ley 1581 de 2012, Colombia)

- Consentimiento de tratamiento registrado por cliente (`customers.consent`).
- El servicio ALPR procesa imágenes en memoria y **no las persiste**. El backend guarda solo la placa sugerida, la confianza y la decisión.
- Política de retención de lecturas de placas, accesos y datos personales: **pendiente de definir con el responsable del tratamiento** (no implementada la purga automática).
- Antes de producción: validar con asesoría legal los avisos de privacidad, retención de imágenes y requisitos vigentes.
