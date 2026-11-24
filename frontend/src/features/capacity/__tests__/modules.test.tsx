import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { CapacityMap } from '../CapacityMap';
import { TariffSimulator } from '../../pricing/TariffSimulator';

const wrap = (ui: ReactNode) => render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider>);
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('CapacityMap', () => {
  it('renders live state per space with accessible text', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('availability')
      ? json({ types: [{ type: 'CAR', active: true, total: 3, available: 1, occupied: 1, reserved: 1, unavailable: 0, spaces: 2 }, { type: 'BICYCLE', active: true, total: 0, available: 0, occupied: 0, reserved: 0, unavailable: 0, spaces: 0 }] })
      : json([{ zoneId: 'z1', name: 'Sótano', level: '-1', spaces: [{ id: 's1', code: 'A1', type: 'CAR', state: 'OCCUPIED' }, { id: 's2', code: 'A2', type: 'CAR', state: 'AVAILABLE' }] }])));
    wrap(<CapacityMap />);
    expect(await screen.findByText('Sótano · Nivel -1')).toBeInTheDocument();
    expect(screen.getByTitle('A1 · Carro · Ocupado')).toHaveClass('state-OCCUPIED');
    expect(screen.getByText(/1 dentro · 1 reservados · 2 cupos físicos/)).toBeInTheDocument();
    expect(screen.getByText(/Capacidad sin configurar/)).toBeInTheDocument();
  });

  it('shows an empty state when no spaces exist', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.includes('availability') ? json({ types: [] }) : json([])));
    wrap(<CapacityMap />);
    expect(await screen.findByText(/no hay cupos configurados/i)).toBeInTheDocument();
  });
});

describe('TariffSimulator', () => {
  it('sends ISO instants and shows the server breakdown', async () => {
    const fetchMock = vi.fn(async (url: string) => url.endsWith('/auth/csrf')
      ? json({ token: 't', headerName: 'X-XSRF-TOKEN' })
      : json({ totalAmount: 41000, billedMinutes: 780, withinGrace: false, minuteRate: 150, fullRate: 32000, blockMinutes: 720, fullRatesReached: 1, fullRatesAmount: 32000, additionalAmount: 9000,
          blocks: [{ number: 1, startsAt: '2026-03-14T00:00:00Z', endsAt: '2026-03-14T12:00:00Z', elapsedMinutes: 720, coveredMinutes: 0, billableMinutes: 720, amount: 32000, fullRateReached: true },
                   { number: 2, startsAt: '2026-03-14T12:00:00Z', endsAt: '2026-03-15T00:00:00Z', elapsedMinutes: 60, coveredMinutes: 0, billableMinutes: 60, amount: 9000, fullRateReached: false }] }));
    vi.stubGlobal('fetch', fetchMock);
    wrap(<TariffSimulator />);
    fireEvent.change(screen.getByLabelText('Entrada'), { target: { value: '2026-03-13T19:00' } });
    fireEvent.change(screen.getByLabelText('Salida'), { target: { value: '2026-03-13T22:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Calcular' }));
    expect(await screen.findByText(/32\.000 · tarifa plena/)).toBeInTheDocument();
    expect(screen.getByText(/780 minutos facturables \(13 h\)/)).toBeInTheDocument();
    expect(screen.getByText('$ 41.000')).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(c => String(c[0]).endsWith('/pricing/quote')) as unknown as [string, RequestInit];
    const body = JSON.parse(String(call[1].body));
    expect(body.vehicleType).toBe('CAR');
    expect(body.entryAt).toMatch(/Z$/);
  });

  it('validates the interval before calling the server', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    wrap(<TariffSimulator />);
    fireEvent.change(screen.getByLabelText('Entrada'), { target: { value: '2026-03-13T22:00' } });
    fireEvent.change(screen.getByLabelText('Salida'), { target: { value: '2026-03-13T19:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Calcular' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/posterior/));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
