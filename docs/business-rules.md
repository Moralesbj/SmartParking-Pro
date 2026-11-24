# Reglas de negocio implementadas

Todas las horas se almacenan como `timestamptz` (UTC) y se muestran en la zona de la sede (por defecto `America/Bogota`). Dinero en `numeric(14,2)` / `BigDecimal`, COP.

## Categorías de vehículo
Solo cuatro: **Motocicleta** (motos y motonetas), **Carro** (automóviles, camionetas y camperos), **Bicicleta** (convencional o eléctrica)
y **Vehículo de carga**. Las subclases no son categorías. La migración V5 convirtió `ELECTRIC`/`ACCESSIBLE` en `CAR` y guardó la
característica en el cupo (`feature` = `EV_CHARGING`/`ACCESSIBLE`). Las bicicletas no tienen placa: se identifican con su registro o
serial (3–12 letras o números).

## Tarifas por vehículo y sede — `vehicle_rates`
- Una fila por sede y categoría: tarifa por minuto, tarifa plena, duración de la tarifa plena (bloque, 720 min por defecto),
  mensualidad, tiempo de cortesía, capacidad y estado. Toda sede nueva recibe los valores de `vehicle_rate_templates`
  (disparador en `branches`); ningún valor monetario está en el código.
- Valores iniciales (plantilla): moto 80/15 000/90 000 · carro 150/32 000/180 000 · bicicleta 30/5 000/40 000 · carga 250/50 000/350 000
  (por minuto / plena cada 12 h / mensualidad), cortesía 0, **capacidad 0** (sin ingresos hasta que la sede la declare).
- Solo ADMIN/SUPERADMIN modifican (`PUT /api/v1/vehicle-rates/{tipo}`); cada cambio crea una versión inmutable
  (`vehicle_rate_versions`, disparadores) y queda en auditoría. Personal operativo solo consulta.

## Motor de cobro — `ParkingRateService` → `ParkingRateCalculationDTO`
- Tiempo desde el instante exacto de ingreso; cada minuto iniciado se cobra ("minuto o fracción"); fracciones de segundo se ignoran.
- Bloques consecutivos de `block_minutes` desde el ingreso, sin límite. En cada bloque: `min(minutos × tarifa por minuto, tarifa plena)`;
  alcanzada la plena, el bloque queda congelado hasta terminar. El bloque siguiente arranca de nuevo por minuto (no cobra otra plena de inmediato).
- No hay reinicio a medianoche: el motor nunca consulta la hora local.
- Cortesía: estancia ≤ cortesía → $0; superada, se cobra desde el minuto 0.
- Minutos que inician dentro de un periodo cubierto por mensualidad no se cobran; periodos solapados se unen (nunca se cobra dos veces).
- Total = bruto − descuentos autorizados (mínimo 0); saldo = total − pagos netos (pagos − reembolsos). BigDecimal escala 2.
- Verificado con la tabla completa de la especificación (25 filas) y los demás tipos (`ParkingRateServiceTest`).

## Entradas, liquidación y salida (M07)
- Entrada idempotente. El cupo físico es opcional: la capacidad se controla por categoría (estancias abiertas + reservas vigentes < capacidad),
  con bloqueo de sede. Categoría inactiva o capacidad 0 → 409.
- La estancia congela tarifa por minuto, plena, bloque, cortesía y versión de la tarifa (RN-012). Cambiar la tarifa no la afecta.
- Cada tiquete tiene un código aleatorio de 6 dígitos (único entre estancias abiertas de la sede) y un QR con `SPK1:<id>`.
- `GET /parking-sessions/lookup?q=` busca por placa, código o QR, solo en la sede del usuario, y devuelve la liquidación completa.
- Liquidar (`POST …/quote`) calcula en tiempo real y crea o reprecia la orden (`priced_at`). Una orden pagada se reabre si la estancia
  se vuelve a liquidar con más tiempo; la salida exige orden pagada y usa el valor pagado (no hay ventana de salida configurada).
- Descuentos: solo supervisión, valor fijo con motivo, nunca mayor que el saldo; idempotentes. El operador no puede fijar el valor.
- Ticket interno: "No es una factura electrónica DIAN".

## Mensualidades (M10)
- Un plan por categoría; el vehículo debe ser de esa categoría. Precio vacío → mensualidad de la categoría en la sede.
- Días permitidos (1 = lunes … 7 = domingo) y franja horaria local opcional (puede cruzar medianoche), días de gracia tras el vencimiento
  y sedes autorizadas adicionales (`PUT /subscription-plans/{id}/branches`, el administrador debe administrar cada sede).
- Cubren solo contratos pagados: `ACTIVE`, o vencidos dentro de la gracia. `PENDING` y `SUSPENDED` no cubren. Al ingresar se marca la
  estancia como de cliente mensualizado si el contrato está en vigor.
- Fecha de vencimiento: por defecto inicio + duración del plan; puede indicarse otra (máximo un año).

## Pagos y caja (M11, M12)
- Pago idempotente por clave; misma clave con otros datos → 409. Nunca se supera el saldo, aun con solicitudes concurrentes.
- Pago no en efectivo exige referencia del comprobante externo. **No se procesan tarjetas** (no hay pasarela).
- Cada pago pertenece a la caja abierta del propio operador. Caja cerrada no recibe pagos ni reversos.
- Cierre: esperado = fondo + efectivo − reembolsos en efectivo + movimientos. Diferencia → alerta `CASH_DIFFERENCE`.
- Reembolso total, solo supervisor, con motivo, una vez por pago.

## Reservas (M08)
- Intervalo `[inicio, fin)`, máximo 30 días, no en el pasado. Exclusión GiST impide solapamientos del mismo cupo.
- Check-in solo dentro del horario y solo por personal. Reserva vencida sin ingreso → `NO_SHOW` (job).
- Las reservas en vigor ocupan capacidad de su categoría. **No implementado:** penalizaciones, políticas de anticipación configurables.

## ALPR y barreras (M14, M15)
- Lectura **auto-aceptada** solo si: placa colombiana válida, el motor no la marcó ambigua, confianza ≥ `ALPR_AUTO_ACCEPT` (0.90), vehículo registrado y no en observación. En cualquier otro caso → `PENDING` (revisión humana).
- Apertura de barrera: lectura `AUTO_ACCEPTED`/`CONFIRMED` de menos de 5 minutos, **o** supervisor con motivo escrito. Barrera debe estar `ONLINE`.
- Comandos con timeout de 3 s; `TIMEOUT`/`FAILED` → alerta. Todo comando queda en `device_commands`, `device_events` y auditoría.
- Sin heartbeat por 2 min → `OFFLINE` + alerta `DEVICE_OFFLINE` (job).

## Turnos (M13)
- Exclusión GiST: un operador no puede tener turnos solapados. Inicio/fin por el propio operador o supervisor.
