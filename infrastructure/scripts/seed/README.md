# Datos iniciales

No hay datos de demostración precargados. El primer administrador se crea una sola vez con
`BOOTSTRAP_EMAIL` y `BOOTSTRAP_PASSWORD` (solo si la tabla `users` está vacía). Después:

1. Inicie sesión y active MFA (obligatorio para administradores).
2. Cree zonas, cupos, tarifas y usuarios desde la interfaz.
3. Elimine `BOOTSTRAP_PASSWORD` del `.env`.

Las credenciales de desarrollo se generan por entorno y nunca se reutilizan en producción.
