import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, downloadReport } from '../../lib/api';
import { money, stateLabels } from '../../lib/format';
import { Button } from '../../components/ui/button';
type Revenue={gross:number;refunds:number;net:number;byMethod:{method:string;total:number;transactions:number}[]};
export function Reports({notify}:{notify:(s:string)=>void}) {
  const [start,setStart]=useState(new Date().toISOString().slice(0,10)); const [end,setEnd]=useState(new Date(Date.now()+86400000).toISOString().slice(0,10));
  const startsAt=new Date(`${start}T00:00:00-05:00`).toISOString(),endsAt=new Date(`${end}T00:00:00-05:00`).toISOString();
  const query=useQuery({queryKey:['revenue',start,end],queryFn:()=>api<Revenue>(`/reports/revenue?startsAt=${encodeURIComponent(startsAt)}&endsAt=${encodeURIComponent(endsAt)}`)});
  const max=Math.max(1,...(query.data?.byMethod.map(m=>Number(m.total))||[]));
  return <><div className="page-heading"><div><span className="eyebrow">Información de negocio</span><h1>Reportes e ingresos</h1><p>Datos transaccionales de la sede, en COP. Fechas en hora Colombia, fin exclusivo.</p></div><Button onClick={()=>void downloadReport(startsAt,endsAt).catch(e=>notify(e.message))}>Exportar CSV</Button></div><section className="panel"><div className="toolbar"><label>Desde<input type="date" value={start} onChange={e=>e.target.value&&setStart(e.target.value)}/></label><label>Hasta (sin incluir)<input type="date" value={end} onChange={e=>e.target.value&&setEnd(e.target.value)}/></label></div>{query.isError?<p className="error" role="alert">{query.error.message}</p>:query.isPending?<p role="status" className="loading">Calculando ingresos…</p>:<><div className="stats report-stats"><article><p>Ingresos brutos</p><strong>{money(query.data.gross)}</strong></article><article><p>Reembolsos</p><strong>{money(query.data.refunds)}</strong></article><article><p>Ingreso neto</p><strong>{money(query.data.net)}</strong></article></div><div className="chart"><h2>Ingresos por medio de pago</h2>{query.data.byMethod.length?query.data.byMethod.map(m=><div key={m.method} className="chart-row"><span>{stateLabels[m.method]}</span><div className="bar-track"><div className="bar" style={{width:`${Number(m.total)/max*100}%`}}/></div><strong>{money(m.total)}</strong><small>{m.transactions} pagos</small></div>):<p className="empty">No hay pagos en este intervalo.</p>}</div></>}</section></>;
}
