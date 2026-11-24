import { useState } from 'react';
import { api } from '../../lib/api';
import { categories, date, minutes, money } from '../../lib/format';
import { Button } from '../../components/ui/button';
import type { Calculation } from '../parking/TicketLookup';

/** Read-only simulator: the server prices a hypothetical stay with the branch's current rates. No records are created. */
export function TariffSimulator() {
  const [result, setResult] = useState<Calculation>();
  const [error, setError] = useState('');
  return <>
    <div className="page-heading"><div><span className="eyebrow">Tarifas</span><h1>Simulador de cobro</h1>
      <p>Calcula una estancia hipotética con la tarifa vigente de la sede: cobro por minuto hasta la tarifa plena en cada bloque, bloques ilimitados. No crea registros.</p></div></div>
    <section className="panel">
      {error && <p role="alert" className="error">{error}</p>}
      <form className="inline-form" onSubmit={async e => {
        e.preventDefault(); setError(''); setResult(undefined);
        const d = new FormData(e.currentTarget);
        const entry = new Date(String(d.get('entry'))), exit = new Date(String(d.get('exit')));
        if (!(exit >= entry)) { setError('La salida debe ser posterior a la entrada'); return; }
        try { setResult(await api<Calculation>('/pricing/quote', 'POST', { vehicleType: d.get('type'), entryAt: entry.toISOString(), exitAt: exit.toISOString() })); }
        catch (err) { setError((err as Error).message); }
      }}>
        <label>Tipo de vehículo<select name="type" defaultValue="CAR">{categories.map(c => <option key={c.type} value={c.type}>{c.label} ({c.includes.toLowerCase()})</option>)}</select></label>
        <label>Entrada<input required type="datetime-local" name="entry" /></label>
        <label>Salida<input required type="datetime-local" name="exit" /></label>
        <Button type="submit">Calcular</Button>
      </form>
      {result && <div className="quote-result" role="status">
        <p className="stat-value">{money(result.totalAmount)}</p>
        <p className="muted">{result.withinGrace ? 'Dentro del tiempo de cortesía' : `${result.billedMinutes} minutos facturables (${minutes(result.billedMinutes)})`} ·
          {' '}{money(result.minuteRate)} por minuto · tarifa plena {money(result.fullRate)} cada {minutes(result.blockMinutes)} ·
          {' '}{result.fullRatesReached} tarifa(s) plena(s) = {money(result.fullRatesAmount)} + cobro adicional {money(result.additionalAmount)}</p>
        <table><thead><tr><th>Bloque</th><th>Desde</th><th>Hasta</th><th>Minutos</th><th>Valor</th></tr></thead>
          <tbody>{result.blocks.slice(0, 200).map(b => <tr key={b.number}><td>{b.number}</td><td>{date(b.startsAt)}</td><td>{date(b.endsAt)}</td><td>{b.elapsedMinutes}</td>
            <td>{money(b.amount)}{b.fullRateReached ? ' · tarifa plena' : ''}</td></tr>)}</tbody></table>
        {result.blocks.length > 200 && <p className="muted">Se muestran los primeros 200 de {result.blocks.length} bloques.</p>}
      </div>}
    </section>
  </>;
}
