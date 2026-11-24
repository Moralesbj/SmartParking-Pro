import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../lib/api';

type Pref = { eventType: string; channel: string; enabled: boolean; providerConfigured: boolean };
const eventText: Record<string, string> = { ReservationConfirmed: 'Reserva confirmada', SubscriptionExpiring: 'Mensualidad por vencer', DeviceOffline: 'Dispositivo sin conexión' };
const channelText: Record<string, string> = { IN_APP: 'Bandeja interna', EMAIL: 'Correo', SMS: 'SMS', WHATSAPP: 'WhatsApp' };

/** Per-user preferences. Channels without a configured provider are shown as such and cannot be enabled. */
export function NotificationPreferences() {
  const qc = useQueryClient();
  const [error, setError] = useState('');
  const prefs = useQuery({ queryKey: ['notification-preferences'], queryFn: () => api<Pref[]>('/notification-preferences') });
  const events = [...new Set(prefs.data?.map(p => p.eventType) || [])];
  return <>
  <div className="page-heading"><div><span className="eyebrow">Notificaciones</span><h1>Preferencias de avisos</h1>
    <p>Elige qué avisos recibes en la bandeja interna. Correo, SMS y WhatsApp se habilitarán cuando haya un proveedor configurado.</p></div></div>
  <section className="panel">
    {error && <p role="alert" className="error">{error}</p>}
    {prefs.isPending ? <p role="status" className="loading">Cargando…</p> : prefs.isError ? <p role="alert" className="error">{prefs.error.message}</p> :
      <table><thead><tr><th>Evento</th>{Object.values(channelText).map(c => <th key={c}>{c}</th>)}</tr></thead>
        <tbody>{events.map(ev => <tr key={ev}><th scope="row">{eventText[ev] || ev}</th>{Object.keys(channelText).map(ch => {
          const p = prefs.data!.find(x => x.eventType === ev && x.channel === ch)!;
          return <td key={ch}>{p.providerConfigured
            ? <input type="checkbox" aria-label={`${eventText[ev] || ev} por ${channelText[ch]}`} checked={p.enabled} onChange={async e => {
                setError('');
                try { await api('/notification-preferences', 'PUT', { eventType: ev, channel: ch, enabled: e.target.checked }); await qc.invalidateQueries({ queryKey: ['notification-preferences'] }); }
                catch (err) { setError((err as Error).message); }
              }} />
            : <span className="muted">Sin proveedor</span>}</td>;
        })}</tr>)}</tbody></table>}
  </section>
  </>;
}
