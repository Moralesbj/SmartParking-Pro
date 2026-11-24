import { display, labels } from '../../lib/format';
import type { ReactNode } from 'react';
export type Row=Record<string,unknown>;
export function DataTable({rows,columns,actions,onSelect}:{rows:Row[];columns:string[];actions?:(row:Row)=>ReactNode;onSelect?:(row:Row)=>void}) {
  if(!rows.length) return <div className="empty"><span>Sin registros todavía</span><p>Los registros aparecerán aquí cuando se realicen operaciones en esta sede.</p></div>;
  return <div className="table-wrap"><table><thead><tr>{columns.map(c=><th key={c}>{labels[c]||c}</th>)}{actions&&<th>Acciones</th>}</tr></thead><tbody>{rows.map((row,i)=><tr key={String(row.id||i)}>{columns.map(c=><td key={c}>{c==='status'||c==='review_status'?<span className={`badge ${String(row[c]).toLowerCase()}`}>{display(row[c],c)}</span>:onSelect&&c===columns[0]?<button className="link" onClick={()=>onSelect(row)}>{display(row[c],c)}</button>:<span title={String(row[c]||'')}>{display(row[c],c)}</span>}</td>)}{actions&&<td><div className="row-actions">{actions(row)}</div></td>}</tr>)}</tbody></table></div>;
}
