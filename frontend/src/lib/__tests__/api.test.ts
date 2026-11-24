import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, setScope, statusMessage } from '../api';
import { display, money } from '../format';

afterEach(() => { vi.unstubAllGlobals(); setScope(undefined); });

describe('api client', () => {
  it('adds scope headers and maps Problem Details with code', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ status: 403, detail: 'Su rol requiere MFA', code: 'MFA_ENROLLMENT_REQUIRED' }), { status: 403 }));
    vi.stubGlobal('fetch', fetchMock);
    setScope({ organization_id: 'org-1', branch_id: 'branch-1' });
    const err = await api('/catalogs/customers').then(() => undefined, (e: unknown) => e as ApiError) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(403);
    expect(err.code).toBe('MFA_ENROLLMENT_REQUIRED');
    expect(err.message).toBe('Su rol requiere MFA');
    const headers = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].headers as Record<string, string>;
    expect(headers['X-Organization-Id']).toBe('org-1');
    expect(headers['X-Branch-Id']).toBe('branch-1');
  });

  it('falls back to useful Spanish messages when the body is empty', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 409 })));
    await expect(api('/x')).rejects.toThrow(/conflicto/);
    expect(statusMessage(500)).toMatch(/servidor/);
    expect(statusMessage(404)).toMatch(/no existe/);
  });

  it('emits session-expired on 401', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 401 })));
    const listener = vi.fn();
    window.addEventListener('session-expired', listener);
    await api('/auth/me').catch(() => undefined);
    expect(listener).toHaveBeenCalled();
    window.removeEventListener('session-expired', listener);
  });
});

describe('format', () => {
  it('formats COP without decimals and translates states', () => {
    expect(money('6000.00').replace(/\s/g, ' ')).toMatch(/6\.000/);
    expect(display('AUTO_ACCEPTED')).toBe('Aceptada automáticamente');
    expect(display('UNKNOWN_STATE')).toBe('UNKNOWN_STATE');
    expect(display('PENDING')).toBe('Pendiente');
    expect(display(null)).toBe('—');
    expect(display(true)).toBe('Sí');
  });
});
