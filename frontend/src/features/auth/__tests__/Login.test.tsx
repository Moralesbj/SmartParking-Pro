import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Login } from '../Login';

type Call = { url: string; method: string; body?: unknown; headers: Record<string, string> };
let calls: Call[] = [];

function mockServer(routes: Record<string, (body: unknown) => { status: number; json?: unknown }>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method || 'GET';
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ url, method, body, headers: (init?.headers || {}) as Record<string, string> });
    if (url.endsWith('/auth/csrf')) return new Response(JSON.stringify({ token: 'csrf-1', headerName: 'X-XSRF-TOKEN' }), { status: 200 });
    const key = `${method} ${url.replace('/api/v1', '')}`;
    const handler = routes[key];
    if (!handler) return new Response(JSON.stringify({ detail: 'no route ' + key }), { status: 404 });
    const res = handler(body);
    return new Response(res.json === undefined ? null : JSON.stringify(res.json), { status: res.status });
  }));
}

function fill(label: RegExp, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }

beforeEach(() => { calls = []; });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('Login', () => {
  it('logs in directly when MFA is not required and sends the CSRF header', async () => {
    mockServer({ 'POST /auth/login': () => ({ status: 200, json: { user: {}, scopes: [] } }) });
    const onLogin = vi.fn();
    render(<Login onLogin={onLogin} />);
    fill(/correo/i, 'op@empresa.co'); fill(/contraseña/i, 'una-clave-larga-123');
    fireEvent.click(screen.getByRole('button', { name: /ingresar/i }));
    await waitFor(() => expect(onLogin).toHaveBeenCalled());
    const login = calls.find(c => c.url.endsWith('/auth/login'))!;
    expect(login.headers['X-XSRF-TOKEN']).toBe('csrf-1');
    expect(login.body).toEqual({ email: 'op@empresa.co', password: 'una-clave-larga-123' });
  });

  it('asks for the TOTP code when the server requires MFA', async () => {
    mockServer({
      'POST /auth/login': () => ({ status: 200, json: { mfaRequired: true } }),
      'POST /auth/mfa/verify': b => (b as { code: string }).code === '123456' ? { status: 200, json: {} } : { status: 401, json: { detail: 'Código inválido' } },
    });
    const onLogin = vi.fn();
    render(<Login onLogin={onLogin} />);
    fill(/correo/i, 'admin@empresa.co'); fill(/contraseña/i, 'una-clave-larga-123');
    fireEvent.click(screen.getByRole('button', { name: /ingresar/i }));
    await screen.findByRole('heading', { name: /código de seguridad/i });
    expect(onLogin).not.toHaveBeenCalled();
    fill(/^código$/i, '000000');
    fireEvent.click(screen.getByRole('button', { name: /verificar/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Código inválido');
    fill(/^código$/i, '123456');
    fireEvent.click(screen.getByRole('button', { name: /verificar/i }));
    await waitFor(() => expect(onLogin).toHaveBeenCalled());
  });

  it('shows server error messages for invalid credentials', async () => {
    mockServer({ 'POST /auth/login': () => ({ status: 401, json: { status: 401, detail: 'Credenciales inválidas' } }) });
    render(<Login onLogin={vi.fn()} />);
    fill(/correo/i, 'x@y.co'); fill(/contraseña/i, 'mala-clave-123456');
    fireEvent.click(screen.getByRole('button', { name: /ingresar/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Credenciales inválidas');
  });

  it('password recovery never reveals whether the account exists', async () => {
    mockServer({ 'POST /auth/forgot-password': () => ({ status: 202, json: { detail: 'ok' } }) });
    render(<Login onLogin={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /olvidaste/i }));
    fill(/correo/i, 'quien@sea.co');
    fireEvent.click(screen.getByRole('button', { name: /enviar instrucciones/i }));
    expect(await screen.findByRole('heading', { name: /revisa tu correo/i })).toBeInTheDocument();
    expect(screen.getByText(/si la cuenta existe/i)).toBeInTheDocument();
  });
});
