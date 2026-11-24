import { useQuery } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { api } from '../../lib/api';
import { stateLabels } from '../../lib/format';
import { Button } from '../../components/ui/button';

type Space = { id: string; code: string; type: string; state: string };
type Zone = { zoneId: string; name: string; level: string; spaces: Space[] };
type TypeSummary = { type: string; active: boolean; total: number; available: number; occupied: number; reserved: number; unavailable: number; spaces: number };

const stateText: Record<string, string> = { AVAILABLE: 'Disponible', OCCUPIED: 'Ocupado', RESERVED: 'Reservado', BLOCKED: 'Bloqueado', MAINTENANCE: 'Mantenimiento' };

/** Visual map of the branch: state is computed by the server from open sessions, reservations and configuration. */
export function CapacityMap() {
  const summary = useQuery({ queryKey: ['capacity', 'availability'], queryFn: () => api<{ types: TypeSummary[] }>('/spaces/availability'), refetchInterval: 15000 });
  const map = useQuery({ queryKey: ['capacity', 'map'], queryFn: () => api<Zone[]>('/spaces/map'), refetchInterval: 15000 });
  return <>
    <div className="page-heading"><div><span className="eyebrow">Operación de la sede</span><h1>Mapa de cupos</h1>
      <p>Capacidad por categoría y estado en vivo de cada cupo físico. Se actualiza cada 15 segundos.</p></div>
      <Button variant="outline" aria-label="Actualizar" onClick={() => { void summary.refetch(); void map.refetch(); }}><RefreshCw size={16} /></Button></div>
    {summary.isError && <p className="error" role="alert">{summary.error.message}</p>}
    <section className="capacity-summary" aria-label="Resumen por tipo">
      {summary.isPending ? <p role="status" className="loading">Cargando disponibilidad…</p> : summary.data?.types.length === 0 ? <p className="empty">No hay cupos configurados en esta sede.</p> :
        summary.data?.types.map(t => <article key={t.type} className="panel stat">
          <h2>{stateLabels[t.type] || t.type}</h2>
          <p className="stat-value">{t.available}<small> / {t.total} libres</small></p>
          <p className="muted">{!t.active ? 'Categoría inactiva · ' : t.total === 0 ? 'Capacidad sin configurar · ' : ''}{t.occupied} dentro · {t.reserved} reservados · {t.spaces} cupos físicos ({t.unavailable} fuera de servicio)</p>
        </article>)}
    </section>
    <ul className="map-legend" aria-label="Convenciones">{Object.entries(stateText).map(([k, v]) => <li key={k}><span className={`space-dot state-${k}`} />{v}</li>)}</ul>
    {map.isPending ? <p role="status" className="loading">Cargando mapa…</p> : map.isError ? <p className="error" role="alert">{map.error.message}</p> :
      map.data.map(z => <section key={z.zoneId} className="panel zone">
        <h2>{z.name}{z.level ? ` · Nivel ${z.level}` : ''}</h2>
        <ul className="space-grid">{z.spaces.map(s => <li key={s.id} className={`space state-${s.state}`} title={`${s.code} · ${stateLabels[s.type] || s.type} · ${stateText[s.state] || s.state}`}>
          <strong>{s.code}</strong><span>{stateLabels[s.type] || s.type}</span><span className="sr-only">{stateText[s.state] || s.state}</span></li>)}</ul>
      </section>)}
  </>;
}
