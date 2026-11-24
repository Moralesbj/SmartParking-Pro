export const money = (value: unknown) => new Intl.NumberFormat('es-CO', { style: 'currency', currency: 'COP', maximumFractionDigits: 0 }).format(Number(value || 0));
export const date = (value: unknown) => value ? new Intl.DateTimeFormat('es-CO', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Bogota' }).format(new Date(String(value))) : '—';
export const labels: Record<string,string> = {
  name:'Nombre',code:'Código',address:'Dirección',timezone:'Zona horaria',document_number:'Documento',email:'Correo',phone:'Teléfono',consent:'Consentimiento de datos',plate:'Placa',country_code:'País',type:'Tipo',customer_id:'Cliente',brand:'Marca',level:'Nivel',zone_id:'Zona',status:'Estado',vehicle_type:'Tipo de vehículo',minute_rate:'Tarifa por minuto',full_rate:'Tarifa plena',grace_minutes:'Cortesía (min)',block_minutes:'Duración tarifa plena (min)',monthly_fee:'Mensualidad',capacity:'Capacidad',feature:'Característica',allowed_days:'Días permitidos (1=lun … 7=dom)',allowed_start:'Desde (hora local)',allowed_end:'Hasta (hora local)',grace_days:'Días de gracia al vencer',ticket_code:'Código',monthly_customer:'Mensualizado',price:'Precio (vacío = mensualidad de la categoría)',duration_days:'Días de vigencia',key:'Parámetro',value:'Valor',full_name:'Nombre completo',role:'Rol',active:'Activo',ticket_number:'Ticket',entry_at:'Entrada',exit_at:'Salida',vehicle_id:'Vehículo',space_id:'Cupo',amount:'Importe',method:'Medio de pago',received_at:'Recibido',created_at:'Creado',starts_at:'Inicio',ends_at:'Fin',opening_balance:'Fondo inicial',expected_balance:'Efectivo esperado',counted_balance:'Efectivo contado',opened_at:'Apertura',closed_at:'Cierre',scheduled_start:'Inicio previsto',scheduled_end:'Fin previsto',operator_id:'Operador',confidence:'Confianza',plate_candidate:'Placa sugerida',review_status:'Revisión',last_heartbeat_at:'Última señal',title:'Asunto',body:'Mensaje',read_at:'Leído',action:'Acción',entity_type:'Entidad',occurred_at:'Fecha',detail:'Detalle',mode:'Modo',reference:'Referencia',order_id:'Orden',session_id:'Estancia',plan_id:'Plan',final_amount:'Importe final',decision_reason:'Motivo de la decisión'
};
export const stateLabels: Record<string,string>={OPEN:'Abierto',CLOSED:'Cerrado',ACTIVE:'Activo',PENDING:'Pendiente',CONFIRMED:'Confirmada',CANCELLED:'Cancelada',CHECKED_IN:'En parqueadero',COMPLETED:'Completada',NO_SHOW:'No asistió',AVAILABLE:'Disponible',BLOCKED:'Bloqueado',MAINTENANCE:'Mantenimiento',CAR:'Carro',MOTORCYCLE:'Motocicleta',BICYCLE:'Bicicleta',CARGO:'Vehículo de carga',STANDARD:'Estándar',EV_CHARGING:'Carga eléctrica',ACCESSIBLE:'Accesible',PENDING_PAYMENT:'Pendiente de pago',GRACE:'En periodo de gracia',NOT_STARTED:'Aún no inicia',NONE:'Sin mensualidad',CASH:'Efectivo',TRANSFER:'Transferencia',CARD:'Tarjeta externa',ONLINE:'En línea',OFFLINE:'Sin conexión',SIMULATOR:'Simulador',PAID:'Pagado',REFUNDED:'Reembolsado',RESOLVED:'Resuelta',ADMIN:'Administrador',SUPERADMIN:'Superadministrador',SUPERVISOR:'Supervisor',OPERATOR:'Operador',CUSTOMER:'Cliente',SCHEDULED:'Programado',ENDED:'Finalizado',EXPIRED:'Vencido',SUSPENDED:'Suspendido',NOT_CONFIGURED:'Sin configurar',REJECTED:'Rechazada',OCCUPIED:'Ocupado',RESERVED:'Reservado',AUTO_ACCEPTED:'Aceptada automáticamente',LATE:'Llegada tarde',ABSENCE:'Ausencia',HANDOVER:'Relevo',INCIDENT:'Incidente',SIMULATED_CAMERA:'Cámara simulada',CAMERA:'Cámara',BARRIER:'Barrera',SENSOR:'Sensor',READER:'Lector'};
export function display(value: unknown, key=''): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Sí' : 'No';
  // Local times of day (e.g. allowed_start "20:00:00") are not instants.
  if (/^\d{2}:\d{2}(:\d{2})?$/.test(String(value))) return String(value).slice(0, 5);
  if (key.endsWith('_at') || key.endsWith('_start') || key.endsWith('_end')) return date(value);
  if (/amount|balance|price|rate|fee/.test(key)) return money(value);
  return stateLabels[String(value)] || String(value);
}

/** Vehicle categories with what each one includes; sub-kinds are never shown as separate categories. */
export const categories = [
  { type: 'MOTORCYCLE', label: 'Motocicleta', includes: 'Motocicletas y motonetas' },
  { type: 'CAR', label: 'Carro', includes: 'Automóviles, camionetas y camperos' },
  { type: 'BICYCLE', label: 'Bicicleta', includes: 'Bicicletas convencionales y eléctricas' },
  { type: 'CARGO', label: 'Vehículo de carga', includes: 'Camiones y transporte de mercancías' },
] as const;

/** 214 → "3 h 34 min"; 720 → "12 h". */
export function minutes(total: number): string {
  const n = Math.max(0, Math.round(Number(total) || 0));
  const d = Math.floor(n / 1440), h = Math.floor((n % 1440) / 60), m = n % 60;
  return [d ? `${d} d` : '', h ? `${h} h` : '', m || !n ? `${m} min` : ''].filter(Boolean).join(' ');
}
