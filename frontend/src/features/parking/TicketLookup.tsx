import { useEffect, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { LogOut, Printer, Receipt, RefreshCw, Search, TicketPercent, Wallet } from 'lucide-react';
import QRCode from 'qrcode';
import { api } from '../../lib/api';
import { date, minutes, money, stateLabels } from '../../lib/format';
import { ActionForm, type InputField } from '../../components/forms/ActionForm';
import { Button } from '../../components/ui/button';

type Block = { number: number; startsAt: string; endsAt: string; elapsedMinutes: number; coveredMinutes: number; billableMinutes: number; amount: number; fullRateReached: boolean };
export type Calculation = {
  staySeconds: number; billedMinutes: number; withinGrace: boolean; minuteRate: number; fullRate: number; blockMinutes: number; graceMinutes: number;
  completedBlocks: number; fullRatesReached: number; fullRatesAmount: number; additionalAmount: number; previousBlocksAmount: number;
  currentBlockNumber: number; currentBlockMinutes: number; currentBlockAmount: number; currentBlockCapped: boolean; minutesUntilFullRate: number | null;
  coveredMinutes: number; grossAmount: number; authorizedDiscounts: number; totalAmount: number; previousPayments: number; balanceDue: number; creditBalance: number;
  settledAt: string; blocks: Block[];
};
export type Liquidation = {
  sessionId: string; ticketNumber: number; ticketCode: string | null; qrPayload: string; plate: string; vehicleType: string; vehicleTypeLabel: string;
  entryAt: string; exitAt: string | null; status: string; ticketStatus: 'PENDING_PAYMENT' | 'PAID' | 'CLOSED'; settled?: boolean; monthlyCustomer: boolean;
  calculation: Calculation | null; finalAmount: number | null;
  subscription: { status: string; planName: string | null; endsAt: string | null; graceUntil: string | null };
  discounts: { id: string; amount: number; reason: string; approved_by_name: string; created_at: string }[];
  order: { id: string; amount: number; status: string; pricedAt: string } | null;
};
type Row = Record<string, unknown>;
const ticketText: Record<string, string> = { PENDING_PAYMENT: 'Pendiente de pago', PAID: 'Pagado · listo para salir', CLOSED: 'Cerrado' };
const subscriptionText: Record<string, string> = { NONE: 'Sin mensualidad', ACTIVE: 'Vigente', GRACE: 'Vencida · en periodo de gracia', EXPIRED: 'Vencida', SUSPENDED: 'Suspendida', PENDING: 'Pendiente de pago', NOT_STARTED: 'Aún no inicia' };

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return <div className="fact"><span>{label}</span><strong>{children}</strong></div>;
}

/** Ticket card with QR (content: SPK1:&lt;id&gt;) and six-digit code; printable. */
export function TicketCard({ l, branch }: { l: Liquidation; branch?: string }) {
  const [qr, setQr] = useState('');
  // SVG output needs no canvas, so it renders the same in every browser and when printing.
  useEffect(() => { void QRCode.toString(l.qrPayload, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' }).then(svg => setQr(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`)); }, [l.qrPayload]);
  return <article className="ticket-card" aria-label="Tiquete">
    <p className="eyebrow">{branch || 'SmartParking Pro'} · Tiquete #{l.ticketNumber}</p>
    {qr && <img className="qr" src={qr} alt={`Código QR del tiquete ${l.ticketCode || ''}`} />}
    {l.ticketCode && <p className="ticket-code" aria-label="Código de seis dígitos">{l.ticketCode}</p>}
    <p><strong>{l.plate}</strong> · {l.vehicleTypeLabel}</p>
    <p className="muted">Ingreso: {date(l.entryAt)}</p>
    <p className="muted">Tiquete interno. No es una factura electrónica DIAN.</p>
  </article>;
}

export function LiquidationDetails({ l }: { l: Liquidation }) {
  const c = l.calculation;
  if (!c) return <p className="muted">Estancia liquidada con el modelo anterior. Valor final: {money(l.finalAmount)}</p>;
  const stayMinutes = Math.floor(c.staySeconds / 60);
  return <>
    <div className="facts">
      <Fact label="Placa">{l.plate}</Fact>
      <Fact label="Tipo de vehículo">{l.vehicleTypeLabel}</Fact>
      <Fact label="Fecha y hora de ingreso">{date(l.entryAt)}</Fact>
      <Fact label="Tiempo total de permanencia">{minutes(stayMinutes)}<small className="muted block">{c.withinGrace ? 'Dentro del tiempo de cortesía' : `${c.billedMinutes} min facturables`}</small></Fact>
      <Fact label="Tarifa por minuto">{money(c.minuteRate)}</Fact>
      <Fact label="Valor de la tarifa plena">{money(c.fullRate)}<small className="muted block">por cada {minutes(c.blockMinutes)}</small></Fact>
      <Fact label={`Bloques completos de ${minutes(c.blockMinutes)}`}>{c.completedBlocks}</Fact>
      <Fact label="Tarifas plenas alcanzadas">{c.fullRatesReached}</Fact>
      <Fact label="Valor acumulado de bloques anteriores">{money(c.previousBlocksAmount)}</Fact>
      <Fact label={`Tiempo del bloque actual (bloque ${c.currentBlockNumber})`}>{minutes(c.currentBlockMinutes)}</Fact>
      <Fact label="Valor generado en el bloque actual">{money(c.currentBlockAmount)}<small className="muted block">{c.currentBlockCapped ? 'Tarifa plena alcanzada: congelado hasta terminar el bloque' : c.minutesUntilFullRate != null ? `Alcanza la tarifa plena en ${minutes(c.minutesUntilFullRate)}` : ''}</small></Fact>
      {c.coveredMinutes > 0 && <Fact label="Cubierto por mensualidad">{minutes(c.coveredMinutes)}</Fact>}
      <Fact label="Descuentos autorizados">{money(c.authorizedDiscounts)}</Fact>
      <Fact label="Pagos previamente realizados">{money(c.previousPayments)}</Fact>
      <Fact label="Saldo pendiente">{money(c.balanceDue)}</Fact>
      <Fact label="Estado de la mensualidad">{subscriptionText[l.subscription.status] || l.subscription.status}{l.subscription.planName && <small className="muted block">{l.subscription.planName}{l.subscription.endsAt ? ` · vence ${date(l.subscription.endsAt)}` : ''}</small>}</Fact>
      <Fact label="Estado del tiquete"><span className={`badge ${l.ticketStatus.toLowerCase()}`}>{ticketText[l.ticketStatus]}</span></Fact>
    </div>
    <div className="total-due" role="status" aria-label="Valor total a pagar"><span>Valor total a pagar</span>{money(c.totalAmount)}
      <small className="muted block">{l.settled ? `Liquidado el ${date(c.settledAt)}` : `Calculado en tiempo real · ${date(c.settledAt)}`}{c.creditBalance > 0 ? ` · Saldo a favor ${money(c.creditBalance)}` : ''}</small></div>
    {c.blocks.length > 0 && <details className="blocks"><summary>Detalle por bloques ({c.blocks.length})</summary>
      <div className="table-wrap"><table><thead><tr><th>Bloque</th><th>Desde</th><th>Hasta</th><th>Minutos</th><th>Cubiertos</th><th>Valor</th></tr></thead>
        <tbody>{c.blocks.map(b => <tr key={b.number}><td>{b.number}</td><td>{date(b.startsAt)}</td><td>{date(b.endsAt)}</td><td>{b.elapsedMinutes}</td><td>{b.coveredMinutes || '—'}</td>
          <td>{money(b.amount)}{b.fullRateReached ? ' · plena' : ''}</td></tr>)}</tbody></table></div></details>}
    {l.discounts.length > 0 && <ul className="discount-list" aria-label="Descuentos">{l.discounts.map(d => <li key={d.id}>{money(d.amount)} · {d.reason} · {d.approved_by_name}</li>)}</ul>}
  </>;
}

/** Operator screen: find a ticket by plate, six-digit code or QR scan, see the liquidation, collect and release. */
export function TicketLookup({ role, notify }: { role: string; notify: (text: string) => void }) {
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [term, setTerm] = useState(params.get('q') || '');
  const [form, setForm] = useState<{ title: string; fields: InputField[]; submit: (b: Record<string, unknown>) => Promise<unknown> }>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const q = params.get('q') || '';
  const result = useQuery({ queryKey: ['ticket', q], enabled: q.length > 0, retry: false, queryFn: () => api<Liquidation>(`/parking-sessions/lookup?q=${encodeURIComponent(q)}`) });
  const l = result.data;
  const supervisor = ['ADMIN', 'SUPERADMIN', 'SUPERVISOR'].includes(role);
  async function act(fn: () => Promise<unknown>, message: string) {
    setError(''); setBusy(true);
    try { await fn(); await qc.invalidateQueries({ queryKey: ['ticket'] }); notify(message); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  /** Liquidates at this instant (the order follows the server's amount), then offers the payment for the balance. */
  async function liquidateAndCollect() {
    setError(''); setBusy(true);
    try {
      const fresh = await api<Liquidation>(`/parking-sessions/${l!.sessionId}/quote`, 'POST', {});
      await qc.invalidateQueries({ queryKey: ['ticket'] });
      if (!fresh.calculation || fresh.calculation.balanceDue <= 0 || !fresh.order) { notify('Sin saldo por pagar. Puede registrar la salida.'); return; }
      const cash = await api<Row[]>('/cash-sessions').catch(() => [] as Row[]);
      const open = cash.filter(c => c.status === 'OPEN').map(c => ({ value: String(c.id), label: `Caja abierta ${date(c.opened_at)}` }));
      if (!open.length) { setError(`Saldo ${money(fresh.calculation.balanceDue)}. Abra su caja en «Caja y arqueos» para cobrar.`); return; }
      setForm({ title: `Cobrar ${money(fresh.calculation.balanceDue)}`, fields: [
        { name: 'cashSessionId', label: 'Caja', required: true, options: open },
        { name: 'method', label: 'Medio de pago', required: true, options: ['CASH', 'TRANSFER', 'CARD'] },
        { name: 'amount', label: 'Importe', type: 'money', required: true, value: fresh.calculation.balanceDue },
        { name: 'reference', label: 'Referencia externa (transferencia o datáfono)', required: false },
      ], submit: async b => { await api('/payments', 'POST', { ...b, orderId: fresh.order!.id, idempotencyKey: crypto.randomUUID() }); await qc.invalidateQueries({ queryKey: ['ticket'] }); notify('Pago registrado'); } });
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <>
    <div className="page-heading"><div><span className="eyebrow">Operación de la sede</span><h1>Consultar tiquete</h1>
      <p>Busque por placa, por el código de seis dígitos o leyendo el código QR del tiquete. El valor lo calcula el servidor; no se puede modificar manualmente.</p></div></div>
    <section className="panel">
      <form className="inline-form" role="search" onSubmit={e => { e.preventDefault(); setError(''); const v = term.trim(); if (v) setParams({ q: v }); }}>
        <label className="grow">Placa, código o QR<input value={term} onChange={e => setTerm(e.target.value)} placeholder="ABC123 · 482915 · lectura del QR" autoFocus aria-label="Placa, código de seis dígitos o contenido del QR" /></label>
        <Button type="submit"><Search size={16} />Consultar</Button>
        {l && <Button type="button" variant="outline" aria-label="Recalcular" onClick={() => void result.refetch()}><RefreshCw size={16} /></Button>}
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      {result.isError && <p role="alert" className="error">{result.error.message}</p>}
      {result.isFetching && !l && <p role="status" className="loading">Buscando…</p>}
      {l && <div className="liquidation">
        <div>
          <LiquidationDetails l={l} />
          {l.status === 'OPEN' && l.calculation && <div className="toolbar actions">
            {l.ticketStatus !== 'PAID' && <Button disabled={busy} onClick={() => void liquidateAndCollect()}><Wallet size={16} />Liquidar y cobrar</Button>}
            {l.ticketStatus === 'PAID' && <Button variant="ghost" disabled={busy} onClick={() => void act(() => api(`/parking-sessions/${l.sessionId}/quote`, 'POST', {}), 'Liquidación actualizada')}><Receipt size={16} />Volver a liquidar</Button>}
            {supervisor && l.calculation.balanceDue > 0 && <Button variant="outline" disabled={busy} onClick={() => setForm({ title: 'Descuento autorizado', fields: [
              { name: 'amount', label: `Valor (máximo ${money(l.calculation!.balanceDue)})`, type: 'money', required: true },
              { name: 'reason', label: 'Motivo', required: true },
            ], submit: async b => { await api(`/parking-sessions/${l.sessionId}/discounts`, 'POST', { ...b, idempotencyKey: crypto.randomUUID() }); await qc.invalidateQueries({ queryKey: ['ticket'] }); notify('Descuento aplicado'); } })}><TicketPercent size={16} />Descuento</Button>}
            {l.ticketStatus === 'PAID' && <Button variant="outline" disabled={busy} onClick={() => { if (window.confirm('¿Registrar la salida del vehículo?')) void act(() => api(`/parking-sessions/${l.sessionId}/check-out`, 'POST', {}), 'Salida registrada'); }}><LogOut size={16} />Registrar salida</Button>}
          </div>}
          {!l.order && l.status === 'OPEN' && <p className="muted">Liquide para generar la orden de cobro con el valor calculado en este momento.</p>}
        </div>
        <aside><TicketCard l={l} /><Button variant="outline" className="print" onClick={() => window.print()}><Printer size={16} />Imprimir tiquete</Button>
          <p className="muted">{stateLabels[l.status] || l.status}{l.exitAt ? ` · salida ${date(l.exitAt)}` : ''}</p></aside>
      </div>}
    </section>
    {form && <ActionForm title={form.title} fields={form.fields} onClose={() => setForm(undefined)} onSubmit={form.submit} />}
  </>;
}
