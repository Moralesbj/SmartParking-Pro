import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { zodResolver } from '@hookform/resolvers/zod';
import { labels, stateLabels } from '../../lib/format';
import { Button } from '../ui/button';
export type InputField={name:string;label?:string;type?:string;required?:boolean;options?:string[]|{value:string;label:string}[];value?:unknown};
export function ActionForm({title,fields,onSubmit,onClose}:{title:string;fields:InputField[];onSubmit:(data:Record<string,unknown>)=>Promise<unknown>;onClose:()=>void}) {
  const shape:Record<string,z.ZodType>={};
  for(const f of fields) shape[f.name]=f.required?z.string().min(1,'Campo obligatorio'):z.string().optional();
  const defaults=Object.fromEntries(fields.map(f=>[f.name,f.value===undefined?(f.options?.length?(typeof f.options[0]==='string'?f.options[0]:f.options[0].value):f.type==='boolean'?'false':''):String(f.value??'')]));
  const {register,handleSubmit,formState:{errors,isSubmitting}}=useForm<Record<string,unknown>>({resolver:zodResolver(z.object(shape)),defaultValues:defaults});
  const [error,setError]=useState('');
  async function submit(values:Record<string,unknown>) {
    setError('');const data={...values};
    for(const f of fields) { if(f.type==='datetime-local'&&data[f.name]) data[f.name]=new Date(String(data[f.name])).toISOString();if(f.type==='boolean') data[f.name]=data[f.name]==='true'; }
    try { await onSubmit(data);onClose(); } catch(e) { setError(e instanceof Error?e.message:'No se pudo guardar'); }
  }
  return <div className="modal-backdrop"><section role="dialog" aria-modal="true" aria-labelledby="form-title" className="modal"><div className="modal-heading"><h2 id="form-title">{title}</h2><Button variant="ghost" onClick={onClose} disabled={isSubmitting} aria-label="Cerrar">×</Button></div><form onSubmit={handleSubmit(submit)}>{error&&<p role="alert" className="error">{error}</p>}<div className="form-grid">{fields.map(f=><label key={f.name}>{f.label||labels[f.name]||f.name}{f.required&&<span aria-hidden="true"> *</span>}{f.options||f.type==='boolean'?<select {...register(f.name)}>{(f.type==='boolean'?[{value:'false',label:'No'},{value:'true',label:'Sí'}]:(f.options||[])).map(o=>typeof o==='string'?<option key={o} value={o}>{stateLabels[o]||o}</option>:<option key={o.value} value={o.value}>{o.label}</option>)}</select>:<input {...register(f.name)} type={f.type==='money'||f.type==='integer'?'number':f.type==='uuid'?'text':f.type||'text'} step={f.type==='money'?'0.01':f.type==='integer'?'1':undefined} min={f.type==='money'||f.type==='integer'?'0':undefined} autoFocus={f===fields[0]}/>}<small className="field-error">{String(errors[f.name]?.message||'')}</small></label>)}</div><div className="modal-footer"><Button variant="outline" type="button" onClick={onClose} disabled={isSubmitting}>Cancelar</Button><Button type="submit" disabled={isSubmitting}>{isSubmitting?'Guardando…':'Guardar'}</Button></div></form></section></div>;
}
