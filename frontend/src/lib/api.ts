export class ApiError extends Error {
  constructor(public status: number, message: string, public code?: string) { super(message); }
}
let scope: { organization_id: string; branch_id: string } | undefined;
let csrf: { token: string; headerName: string } | undefined;
export function setScope(next: typeof scope) { scope = next; }
export async function refreshCsrf() {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
  if (!response.ok) throw new ApiError(response.status, 'No se pudo establecer una conexión segura');
  csrf = await response.json() as { token: string; headerName: string };
}
/** Useful messages when the server sent no Problem Details body. */
export function statusMessage(status: number): string {
  switch (status) {
    case 401: return 'Su sesión no está activa. Inicie sesión de nuevo';
    case 403: return 'No tiene permiso para esta acción en la sede seleccionada';
    case 404: return 'El registro no existe o no pertenece a su sede';
    case 409: return 'La operación entra en conflicto con el estado actual; actualice e intente de nuevo';
    case 422: return 'Revise los datos ingresados';
    case 429: return 'Demasiadas solicitudes. Espere un momento';
    default: return status >= 500 ? 'El servidor no pudo completar la operación. Intente más tarde' : `Error ${status}. Intente nuevamente`;
  }
}
export async function api<T = unknown>(path: string, method = 'GET', body?: unknown): Promise<T> {
  if (method !== 'GET' && !csrf) await refreshCsrf();
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (csrf && method !== 'GET') headers[csrf.headerName] = csrf.token;
  if (scope) { headers['X-Organization-Id'] = scope.organization_id; headers['X-Branch-Id'] = scope.branch_id; }
  const response = await fetch(`/api/v1${path}`, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { detail?: string; fields?: string[]; code?: string };
    if (response.status === 401) window.dispatchEvent(new Event('session-expired'));
    throw new ApiError(response.status, error.fields?.join('. ') || error.detail || statusMessage(response.status), error.code);
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T;
  const text = await response.text(); return (text ? JSON.parse(text) : undefined) as T;
}
export async function downloadReport(startsAt: string, endsAt: string) {
  const headers: Record<string,string> = {};
  if(scope) { headers['X-Organization-Id']=scope.organization_id;headers['X-Branch-Id']=scope.branch_id; }
  const response=await fetch(`/api/v1/reports/export?startsAt=${encodeURIComponent(startsAt)}&endsAt=${encodeURIComponent(endsAt)}`,{headers,credentials:'same-origin'});
  if(!response.ok) throw new Error('No se pudo exportar el reporte');
  const url=URL.createObjectURL(await response.blob()); const a=document.createElement('a');a.href=url;a.download='ingresos.csv';a.click();URL.revokeObjectURL(url);
}
