import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../lib/api';
import { Button } from '../../components/ui/button';

type Branch = { id: string; name: string; code: string; own?: boolean };

/** Branches where a monthly plan's contracts apply. The plan's own branch is always included. */
export function PlanBranches({ plan, onClose, notify }: { plan: { id: string; name: string }; onClose: () => void; notify: (t: string) => void }) {
  const all = useQuery({ queryKey: ['catalog', 'branches', 'all'], queryFn: () => api<{ items: Branch[] }>('/catalogs/branches?size=100') });
  const current = useQuery({ queryKey: ['plan-branches', plan.id], queryFn: () => api<Branch[]>(`/subscription-plans/${plan.id}/branches`) });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState('');
  const own = current.data?.find(b => b.own)?.id;
  useEffect(() => { if (current.data) setSelected(new Set(current.data.map(b => b.id))); }, [current.data]);
  return <div className="modal-backdrop"><section role="dialog" aria-modal="true" aria-labelledby="plan-branches" className="modal">
    <div className="modal-heading"><h2 id="plan-branches">Sedes autorizadas · {plan.name}</h2><Button variant="ghost" aria-label="Cerrar" onClick={onClose}>×</Button></div>
    <form className="modal-body" onSubmit={async e => {
      e.preventDefault(); setError('');
      try { await api(`/subscription-plans/${plan.id}/branches`, 'PUT', { branchIds: [...selected].filter(id => id !== own) }); notify('Sedes autorizadas actualizadas'); onClose(); }
      catch (err) { setError((err as Error).message); }
    }}>
      {error && <p role="alert" className="error">{error}</p>}
      {all.isPending || current.isPending ? <p role="status" className="loading">Cargando sedes…</p> : all.isError ? <p role="alert" className="error">{all.error.message}</p> :
        <fieldset className="checklist"><legend>Los contratos de este plan cubren al vehículo en las sedes marcadas.</legend>
          {all.data!.items.map(b => <label key={b.id}><input type="checkbox" disabled={b.id === own} checked={selected.has(b.id)}
            onChange={e => setSelected(s => { const n = new Set(s); if (e.target.checked) n.add(b.id); else n.delete(b.id); return n; })} />
            {b.name} <small className="muted">{b.code}{b.id === own ? ' · sede del plan' : ''}</small></label>)}
        </fieldset>}
      <div className="modal-footer"><Button variant="outline" type="button" onClick={onClose}>Cancelar</Button><Button type="submit">Guardar</Button></div>
    </form>
  </section></div>;
}
