import { useState, type FormEvent } from 'react';
import { Car, CircleParking, KeyRound, LogIn, ShieldCheck } from 'lucide-react';
import { api, refreshCsrf } from '../../lib/api';
import { Button } from '../../components/ui/button';

type Step = 'password' | 'mfa' | 'forgot' | 'forgot-sent';

/** Login with optional TOTP second step and self-service password recovery. */
export function Login({ onLogin }: { onLogin: () => void }) {
  const [step, setStep] = useState<Step>('password');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [useRecovery, setUseRecovery] = useState(false);

  async function run(e: FormEvent<HTMLFormElement>, action: (data: FormData) => Promise<void>) {
    e.preventDefault(); setError(''); setBusy(true);
    try { await action(new FormData(e.currentTarget)); } catch (err) { setError((err as Error).message); } finally { setBusy(false); }
  }

  const password = async (data: FormData) => {
    const res = await api<{ mfaRequired?: boolean }>('/auth/login', 'POST', { email: data.get('email'), password: data.get('password') });
    await refreshCsrf();
    if (res?.mfaRequired) setStep('mfa'); else onLogin();
  };
  const mfa = async (data: FormData) => {
    const body = useRecovery ? { recoveryCode: String(data.get('code') || '').trim() } : { code: String(data.get('code') || '').trim() };
    await api('/auth/mfa/verify', 'POST', body);
    await refreshCsrf(); onLogin();
  };
  const forgot = async (data: FormData) => { await api('/auth/forgot-password', 'POST', { email: data.get('email') }); setStep('forgot-sent'); };

  return <div className="login">
    <div className="login-art"><div className="brand"><CircleParking size={36} /><span>SmartParking<em>PRO</em></span></div>
      <div><span className="eyebrow">CONTROL, CLARIDAD Y MOVIMIENTO</span><h1>El siguiente espacio<br />para tu negocio.</h1><p>Administra tus sedes, vehículos y operaciones desde un mismo lugar.</p><div className="parking-art" aria-hidden="true"><span>P</span><Car size={112} /></div></div>
      <small>SmartParking Pro · Gestión de parqueaderos</small></div>
    <main className="login-form"><div>
      {error && <p role="alert" className="error">{error}</p>}
      {step === 'password' && <>
        <span className="eyebrow">BIENVENIDO DE NUEVO</span><h1>Inicia sesión</h1><p>Ingresa con la cuenta asignada por tu administrador.</p>
        <form onSubmit={e => void run(e, password)}>
          <label>Correo electrónico<input required name="email" type="email" autoComplete="username" placeholder="nombre@empresa.com" /></label>
          <label>Contraseña<input required name="password" type="password" autoComplete="current-password" maxLength={72} /></label>
          <Button type="submit" disabled={busy}>{busy ? 'Ingresando…' : 'Ingresar a mi cuenta'}<LogIn size={17} /></Button>
        </form>
        <Button variant="ghost" type="button" onClick={() => { setError(''); setStep('forgot'); }}>¿Olvidaste tu contraseña?</Button>
      </>}
      {step === 'mfa' && <>
        <span className="eyebrow">VERIFICACIÓN EN DOS PASOS</span><h1>Código de seguridad</h1>
        <p>{useRecovery ? 'Ingresa uno de tus códigos de recuperación. Cada código sirve una sola vez.' : 'Ingresa el código de 6 dígitos de tu aplicación autenticadora.'}</p>
        <form onSubmit={e => void run(e, mfa)}>
          <label>{useRecovery ? 'Código de recuperación' : 'Código'}<input required name="code" autoComplete="one-time-code" inputMode={useRecovery ? 'text' : 'numeric'} pattern={useRecovery ? '[A-Za-z0-9]{6,20}' : '\\d{6}'} maxLength={useRecovery ? 20 : 6} /></label>
          <Button type="submit" disabled={busy}>{busy ? 'Verificando…' : 'Verificar'}<KeyRound size={17} /></Button>
        </form>
        <Button variant="ghost" type="button" onClick={() => setUseRecovery(r => !r)}>{useRecovery ? 'Usar código de la aplicación' : 'Usar un código de recuperación'}</Button>
      </>}
      {step === 'forgot' && <>
        <span className="eyebrow">RECUPERAR ACCESO</span><h1>Restablecer contraseña</h1><p>Te enviaremos un enlace válido por 30 minutos si la cuenta existe.</p>
        <form onSubmit={e => void run(e, forgot)}>
          <label>Correo electrónico<input required name="email" type="email" autoComplete="username" /></label>
          <Button type="submit" disabled={busy}>Enviar instrucciones</Button>
        </form>
        <Button variant="ghost" type="button" onClick={() => setStep('password')}>Volver al inicio de sesión</Button>
      </>}
      {step === 'forgot-sent' && <>
        <h1>Revisa tu correo</h1>
        <p>Si la cuenta existe, se enviaron instrucciones. Si el envío de correo no está configurado en esta instalación, pide al administrador que restablezca tu acceso.</p>
        <Button type="button" onClick={() => setStep('password')}>Volver al inicio de sesión</Button>
      </>}
      <p className="login-note"><ShieldCheck size={16} />Sesión protegida y acceso por organización y sede.</p>
    </div></main>
  </div>;
}

/** Landing page for the e-mailed reset link: /restablecer?token=… */
export function ResetPassword() {
  const token = new URLSearchParams(window.location.search).get('token') || '';
  const [done, setDone] = useState(false);
  const [error, setError] = useState('');
  return <main className="login-form standalone"><div>
    <span className="eyebrow">RECUPERAR ACCESO</span><h1>Nueva contraseña</h1>
    {done ? <><p role="status">Contraseña actualizada. Las sesiones anteriores se cerraron.</p><a href="/">Iniciar sesión</a></> : <>
      {!token && <p role="alert" className="error">El enlace no contiene un token válido.</p>}
      {error && <p role="alert" className="error">{error}</p>}
      <form onSubmit={async e => {
        e.preventDefault(); setError('');
        const data = new FormData(e.currentTarget);
        if (data.get('password') !== data.get('confirm')) { setError('Las contraseñas no coinciden'); return; }
        try { await api('/auth/reset-password', 'POST', { token, newPassword: data.get('password') }); setDone(true); } catch (err) { setError((err as Error).message); }
      }}>
        <label>Nueva contraseña (8 a 72 caracteres)<input required name="password" type="password" minLength={8} maxLength={72} autoComplete="new-password" /></label>
        <label>Confirmar contraseña<input required name="confirm" type="password" minLength={8} maxLength={72} autoComplete="new-password" /></label>
        <Button type="submit" disabled={!token}>Guardar contraseña</Button>
      </form></>}
  </div></main>;
}
