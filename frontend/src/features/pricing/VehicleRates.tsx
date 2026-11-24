import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { History, Pencil } from 'lucide-react';
import { api } from '../../lib/api';
import { date, minutes, money } from '../../lib/format';
import { ActionForm } from '../../components/forms/ActionForm';
import { Button } from '../../components/ui/button';

export type VehicleRate = {
  id: string; vehicle_type: string; label: string; includes: string; minute_rate: number; full_rate: number; block_minutes: number;
  monthly_fee: number; grace_minutes: number; capacity: number; active: boolean; version: number; minutes_to_full_rate: number; updated_at: string;
};
type Version = { version: number; minute_rate: number; full_rate: number; block_minutes: number; monthly_fee: number; grace_minutes: number; capacity: number; active: boolean; changed_at: string; changed_by_name: string | null };

/** Per-branch configuration of the four vehicle categories. Values live in the database; nothing is fixed in code. */
export function VehicleRates({ role, notify }: { role: string; notify: (text: string) => void }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<VehicleRate>();
  const [history, setHistory] = useState<VehicleRate>();
  const rates = useQuery({ queryKey: ['vehicle-rates'], queryFn: () => api<VehicleRate[]>('/vehicle-rates') });
  const versions = useQuery({ queryKey: ['vehicle-rates', 'history', history?.vehicle_type], enabled: !!history, queryFn: () => api<Version[]>(`/vehicle-rates/${history!.vehicle_type}/history`) });
  const admin = ['ADMIN', 'SUPERADMIN'].includes(role), supervisor = admin || role === 'SUPERVISOR';
  return <>
    <div className="page-heading"><div><span className="eyebrow">Tarifas de la sede</span><h1>Tarifas por vehículo</h1>
      <p>Se cobra por minuto desde la hora exacta de ingreso hasta alcanzar la tarifa plena; el valor queda congelado hasta completar el bloque y luego empieza un bloque nuevo. No hay reinicio a medianoche. Cada entrada conserva la tarifa vigente al ingresar.</p></div></div>
    <section className="panel">
      {rates.isPending ? <p role="status" className="loading">Cargando tarifas…</p> : rates.isError ? <p role="alert" className="error">{rates.error.message}</p> :
        <div className="table-wrap"><table>
          <thead><tr><th>Categoría</th><th>Por minuto</th><th>Tarifa plena</th><th>Se alcanza a los</th><th>Cobertura</th><th>Mensualidad</th><th>Cortesía</th><th>Capacidad</th><th>Estado</th><th>Acciones</th></tr></thead>
          <tbody>{rates.data.map(r => <tr key={r.vehicle_type}>
            <th scope="row">{r.label}<small className="muted block">{r.includes}</small></th>
            <td>{money(r.minute_rate)}</td><td>{money(r.full_rate)}</td>
            <td>{Number(r.minutes_to_full_rate) <= r.block_minutes ? minutes(r.minutes_to_full_rate) : 'No se alcanza en el bloque'}</td>
            <td>{minutes(r.block_minutes)}</td><td>{money(r.monthly_fee)}</td><td>{r.grace_minutes ? `${r.grace_minutes} min` : 'Sin cortesía'}</td>
            <td>{r.capacity === 0 ? <span className="badge pending">Sin configurar</span> : r.capacity}</td>
            <td><span className={`badge ${r.active ? 'active' : 'suspended'}`}>{r.active ? 'Activa' : 'Inactiva'}</span></td>
            <td><div className="row-actions">
              {admin && <Button size="sm" variant="outline" onClick={() => setEditing(r)}><Pencil size={14} />Editar</Button>}
              {supervisor && <Button size="sm" variant="ghost" onClick={() => setHistory(r)}><History size={14} />Historial v{r.version}</Button>}
            </div></td>
          </tr>)}</tbody></table></div>}
      <p className="muted">La capacidad limita cuántos vehículos de cada categoría pueden estar dentro al mismo tiempo (estancias abiertas más reservas vigentes). Con capacidad 0 la categoría no admite ingresos.</p>
    </section>
    {editing && <ActionForm title={`Editar tarifa · ${editing.label}`} onClose={() => setEditing(undefined)} fields={[
      { name: 'minuteRate', label: 'Tarifa por minuto (COP)', type: 'money', required: true, value: editing.minute_rate },
      { name: 'fullRate', label: 'Tarifa plena (COP)', type: 'money', required: true, value: editing.full_rate },
      { name: 'blockMinutes', label: 'Duración de la tarifa plena (minutos; 720 = 12 h)', type: 'integer', required: true, value: editing.block_minutes },
      { name: 'monthlyFee', label: 'Mensualidad (COP)', type: 'money', required: true, value: editing.monthly_fee },
      { name: 'graceMinutes', label: 'Tiempo de cortesía (minutos)', type: 'integer', required: true, value: editing.grace_minutes },
      { name: 'capacity', label: 'Capacidad de estacionamiento', type: 'integer', required: true, value: editing.capacity },
      { name: 'active', label: 'Categoría activa', type: 'boolean', required: true, value: String(editing.active) },
    ]} onSubmit={async body => {
      const numeric = Object.fromEntries(Object.entries(body).map(([k, v]) => [k, k === 'active' ? v : Number(v)]));
      await api(`/vehicle-rates/${editing.vehicle_type}`, 'PUT', numeric);
      await qc.invalidateQueries({ queryKey: ['vehicle-rates'] });
      notify(`Tarifa de ${editing.label} actualizada. Las estancias abiertas conservan su tarifa de ingreso.`);
    }} />}
    {history && <div className="modal-backdrop"><section role="dialog" aria-modal="true" aria-labelledby="rate-history" className="modal wide">
      <div className="modal-heading"><h2 id="rate-history">Historial · {history.label}</h2><Button variant="ghost" aria-label="Cerrar" onClick={() => setHistory(undefined)}>×</Button></div>
      {versions.isPending ? <p role="status" className="loading">Cargando…</p> : versions.isError ? <p role="alert" className="error">{versions.error.message}</p> :
        <div className="table-wrap"><table><thead><tr><th>Versión</th><th>Fecha</th><th>Por minuto</th><th>Plena</th><th>Bloque</th><th>Mensualidad</th><th>Cortesía</th><th>Capacidad</th><th>Activa</th><th>Por</th></tr></thead>
          <tbody>{versions.data.map(v => <tr key={v.version}><td>v{v.version}</td><td>{date(v.changed_at)}</td><td>{money(v.minute_rate)}</td><td>{money(v.full_rate)}</td><td>{minutes(v.block_minutes)}</td>
            <td>{money(v.monthly_fee)}</td><td>{v.grace_minutes} min</td><td>{v.capacity}</td><td>{v.active ? 'Sí' : 'No'}</td><td>{v.changed_by_name || 'Sistema'}</td></tr>)}</tbody></table></div>}
    </section></div>}
  </>;
}
