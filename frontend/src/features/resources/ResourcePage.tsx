import { useEffect, useState } from 'react';
import { PlanBranches } from '../subscriptions/PlanBranches';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, RefreshCw, Search } from 'lucide-react';
import { api } from '../../lib/api';
import { ActionForm, type InputField } from '../../components/forms/ActionForm';
import { DataTable, type Row } from '../../components/shared/DataTable';
import { Button } from '../../components/ui/button';
import { labels } from '../../lib/format';

type Field=InputField & {type:string;required:boolean;options:string[]};
type Catalog={fields:Field[];operatorWrite:boolean};
const referenceCatalogs:Record<string,string>={zone_id:'zones',customer_id:'customers'};
export function ResourcePage({catalog,title,description,role,notify}:{catalog:string;title:string;description:string;role:string;notify:(text:string)=>void}) {
  const qc=useQueryClient();const [page,setPage]=useState(0);const [search,setSearch]=useState('');const [editing,setEditing]=useState<Row|undefined>();const [creating,setCreating]=useState(false);const [branchesOf,setBranchesOf]=useState<Row>();
  const definition=useQuery({queryKey:['definition',catalog],queryFn:()=>api<Catalog>(`/catalogs/${catalog}/definition`)});
  const data=useQuery({queryKey:['catalog',catalog,page,search],queryFn:()=>api<{items:Row[];total:number}>(`/catalogs/${catalog}?page=${page}&search=${encodeURIComponent(search)}`)});
  const canWrite=['ADMIN','SUPERADMIN'].includes(role)||definition.data?.operatorWrite;
  // Reference fields (uuid) are offered as a list from the related catalog instead of a raw identifier.
  const refFields=(definition.data?.fields||[]).filter(f=>f.type==='uuid'&&referenceCatalogs[f.name]);
  const refs=useQuery({queryKey:['refs',catalog,refFields.map(f=>f.name).join()],enabled:(creating||!!editing)&&refFields.length>0,queryFn:async()=>Object.fromEntries(await Promise.all(refFields.map(async f=>{
    const data=await api<{items:Row[]}>(`/catalogs/${referenceCatalogs[f.name]}?size=100`);
    const options=data.items.map(r=>({value:String(r.id),label:String(r.name||r.code||r.plate||r.id)}));
    return [f.name,f.required?options:[{value:'',label:'— Ninguno —'},...options]] as const;
  })))});
  useEffect(()=>{setPage(0);setSearch('');setEditing(undefined);setCreating(false);},[catalog]);
  async function save(body:Record<string,unknown>) {
    await api(`/catalogs/${catalog}${editing?`/${editing.id}`:''}`,editing?'PUT':'POST',body);
    await qc.invalidateQueries();notify(editing?'Cambios guardados':'Registro creado');
  }
  const columns=definition.data?.fields.map(f=>f.name).filter(n=>n!=='consent'&&n!=='value').slice(0,6)||[];
  return <><div className="page-heading"><div><span className="eyebrow">Administración de la sede</span><h1>{title}</h1><p>{description}</p></div>{canWrite&&<Button onClick={()=>setCreating(true)}><Plus size={16}/>Agregar</Button>}</div><section className="panel"><div className="toolbar"><div className="search-box"><Search size={16}/><input aria-label={`Buscar ${title}`} placeholder="Buscar registros…" value={search} onChange={e=>{setSearch(e.target.value);setPage(0);}}/></div><Button variant="outline" onClick={()=>void data.refetch()} aria-label="Actualizar"><RefreshCw size={16}/></Button></div>{data.isPending?<p className="loading" role="status">Cargando registros…</p>:data.isError?<p className="error" role="alert">{data.error.message}</p>:<DataTable rows={data.data.items} columns={columns} actions={canWrite?row=><><Button size="sm" variant="outline" onClick={()=>setEditing(row)}>Editar</Button>{catalog==='subscription-plans'&&<Button size="sm" variant="ghost" onClick={()=>setBranchesOf(row)}>Sedes autorizadas</Button>}</>:undefined}/>}<div className="pagination"><span>{data.data?.total||0} registros</span><Button variant="ghost" disabled={page===0} onClick={()=>setPage(p=>p-1)}>Anterior</Button><span>Página {page+1}</span><Button variant="ghost" disabled={(page+1)*20>=(data.data?.total||0)} onClick={()=>setPage(p=>p+1)}>Siguiente</Button></div></section>{(creating||editing)&&definition.data&&<ActionForm title={`${editing?'Editar':'Agregar'} ${title.toLowerCase()}`} fields={definition.data.fields.map(f=>({...f,options:f.options.length?f.options:refs.data?.[f.name],value:editing?.[f.name]}))} onSubmit={save} onClose={()=>{setCreating(false);setEditing(undefined);}}/>}{branchesOf&&<PlanBranches plan={{id:String(branchesOf.id),name:String(branchesOf.name)}} notify={notify} onClose={()=>setBranchesOf(undefined)}/>}</>;
}
