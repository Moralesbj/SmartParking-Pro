import { useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { api } from '../../lib/api';
import { Button } from '../../components/ui/button';

/** MFA (TOTP) enrollment. Required by the server for administrator roles. */
export function SecurityPage({ mfaEnabled, onChanged }: { mfaEnabled: boolean; onChanged: () => void }) {
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string }>();
  const [codes, setCodes] = useState<string[]>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function start() {
    setError(''); setBusy(true);
    try { setSetup(await api('/auth/mfa/setup', 'POST')); } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return <>
    <div className="page-heading"><div><span className="eyebrow">Seguridad de la cuenta</span><h1>Verificación en dos pasos</h1>
      <p>Los administradores deben usar una aplicación autenticadora (TOTP). El acceso a cada sede se valida en el servidor en cada solicitud.</p></div></div>
    <section className="panel security-info">
      {error && <p role="alert" className="error">{error}</p>}
      {codes ? <>
        <h2>Códigos de recuperación</h2>
        <p>Guárdalos en un lugar seguro. Cada uno sirve una vez y no se volverán a mostrar.</p>
        <ul className="recovery-codes">{codes.map(c => <li key={c}><code>{c}</code></li>)}</ul>
        <Button onClick={() => { setCodes(undefined); onChanged(); }}>Ya los guardé</Button>
      </> : mfaEnabled ? <p><ShieldCheck size={16} /> La verificación en dos pasos está activa en tu cuenta.</p> : !setup ? <>
        <p>La verificación en dos pasos no está activa.</p>
        <Button onClick={() => void start()} disabled={busy}>Configurar aplicación autenticadora</Button>
      </> : <>
        <h2>1. Agrega la cuenta a tu aplicación</h2>
        <p>Ingresa esta clave manualmente en tu aplicación autenticadora (tipo: basada en tiempo):</p>
        <p><code className="secret">{setup.secret.match(/.{1,4}/g)?.join(' ')}</code></p>
        <p className="muted">Enlace de configuración: <a href={setup.otpauthUri}>abrir en la aplicación</a></p>
        <h2>2. Confirma con un código</h2>
        <form onSubmit={async e => {
          e.preventDefault(); setError('');
          const code = String(new FormData(e.currentTarget).get('code') || '');
          try { const res = await api<{ recoveryCodes: string[] }>('/auth/mfa/enable', 'POST', { code }); setCodes(res.recoveryCodes); setSetup(undefined); }
          catch (err) { setError((err as Error).message); }
        }}>
          <label>Código de 6 dígitos<input required name="code" inputMode="numeric" pattern="\d{6}" maxLength={6} autoComplete="one-time-code" /></label>
          <Button type="submit">Activar</Button>
        </form>
      </>}
      <h2>Controles de sesión</h2>
      <p>Contraseñas BCrypt, cookie de sesión HttpOnly con SameSite=Strict, CSRF, nueva sesión al ingresar, bloqueo temporal por intentos fallidos y revocación automática de sesiones al cambiar la contraseña o suspender la cuenta.</p>
    </section>
  </>;
}
