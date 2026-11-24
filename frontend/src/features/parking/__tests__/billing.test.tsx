import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { ReactNode } from 'react';
import { TicketLookup, type Liquidation } from '../TicketLookup';
import { VehicleRates } from '../../pricing/VehicleRates';
import { minutes } from '../../../lib/format';

const wrap = (ui: ReactNode, path = '/') => render(<MemoryRouter initialEntries={[path]}><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider></MemoryRouter>);
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const liquidation = (over: Partial<Liquidation> = {}): Liquidation => ({
  sessionId: 's1', ticketNumber: 17, ticketCode: '048213', qrPayload: 'SPK1:s1', plate: 'ABC123', vehicleType: 'CAR', vehicleTypeLabel: 'Carro',
  entryAt: '2026-03-14T08:00:00Z', exitAt: null, status: 'OPEN', ticketStatus: 'PENDING_PAYMENT', settled: false, monthlyCustomer: false, finalAmount: null,
  subscription: { status: 'NONE', planName: null, endsAt: null, graceUntil: null }, discounts: [], order: null,
  calculation: {
    staySeconds: 46800, billedMinutes: 780, withinGrace: false, minuteRate: 150, fullRate: 32000, blockMinutes: 720, graceMinutes: 0,
    completedBlocks: 1, fullRatesReached: 1, fullRatesAmount: 32000, additionalAmount: 9000, previousBlocksAmount: 32000,
    currentBlockNumber: 2, currentBlockMinutes: 60, currentBlockAmount: 9000, currentBlockCapped: false, minutesUntilFullRate: 154,
    coveredMinutes: 0, grossAmount: 41000, authorizedDiscounts: 0, totalAmount: 41000, previousPayments: 0, balanceDue: 41000, creditBalance: 0,
    settledAt: '2026-03-14T21:00:00Z', blocks: [],
  },
  ...over,
});

describe('format.minutes', () => {
  it('renders durations the way operators read them', () => {
    expect(minutes(214)).toBe('3 h 34 min');
    expect(minutes(720)).toBe('12 h');
    expect(minutes(10080)).toBe('7 d');
    expect(minutes(0)).toBe('0 min');
  });
});

describe('TicketLookup', () => {
  it('looks up by the query in the URL and shows every liquidation field', async () => {
    const fetchMock = vi.fn(async (_url: string) => json(liquidation()));
    vi.stubGlobal('fetch', fetchMock);
    wrap(<TicketLookup role="OPERATOR" notify={() => {}} />, '/ticket-lookup?q=048213');
    expect((await screen.findAllByText('ABC123')).length).toBe(2); // liquidation and printable ticket
    expect(String(fetchMock.mock.calls[0][0])).toContain('/parking-sessions/lookup?q=048213');
    for (const label of ['Placa', 'Tipo de vehículo', 'Fecha y hora de ingreso', 'Tiempo total de permanencia', 'Tarifa por minuto', 'Valor de la tarifa plena',
      'Bloques completos de 12 h', 'Tarifas plenas alcanzadas', 'Valor acumulado de bloques anteriores', 'Tiempo del bloque actual (bloque 2)',
      'Valor generado en el bloque actual', 'Descuentos autorizados', 'Pagos previamente realizados', 'Saldo pendiente', 'Estado de la mensualidad', 'Estado del tiquete'])
      expect(screen.getByText(label)).toBeInTheDocument();
    expect(within(screen.getByLabelText('Valor total a pagar')).getByText(/41\.000/)).toBeInTheDocument();
    expect(screen.getByText('Alcanza la tarifa plena en 2 h 34 min')).toBeInTheDocument();
    expect(screen.getByLabelText('Código de seis dígitos')).toHaveTextContent('048213');
    await waitFor(() => expect(screen.getByAltText(/Código QR del tiquete/)).toHaveAttribute('src', expect.stringMatching(/^data:image\/svg\+xml/)));
    // Operators liquidate and collect but cannot grant discounts or exit an unpaid ticket.
    expect(screen.getByRole('button', { name: /Liquidar y cobrar/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Descuento/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Registrar salida/ })).not.toBeInTheDocument();
  });

  it('offers discounts to supervisors and exit once paid', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(liquidation({ ticketStatus: 'PAID', order: { id: 'o1', amount: 41000, status: 'PAID', pricedAt: '' } }))));
    wrap(<TicketLookup role="SUPERVISOR" notify={() => {}} />, '/ticket-lookup?q=ABC123');
    expect(await screen.findByRole('button', { name: /Registrar salida/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Descuento/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Liquidar y cobrar/ })).not.toBeInTheDocument();
  });

  it('shows the server error when the ticket does not exist', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ title: 'Not Found', status: 404, detail: 'No se encontró un tiquete para «999999» en esta sede' }, 404)));
    wrap(<TicketLookup role="OPERATOR" notify={() => {}} />, '/ticket-lookup');
    fireEvent.change(screen.getByLabelText(/contenido del QR/), { target: { value: '999999' } });
    fireEvent.click(screen.getByRole('button', { name: /Consultar/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No se encontró un tiquete');
  });
});

describe('VehicleRates', () => {
  const rates = [
    { id: 'r1', vehicle_type: 'MOTORCYCLE', label: 'Motocicleta', includes: 'Motocicletas y motonetas', minute_rate: 80, full_rate: 15000, block_minutes: 720, monthly_fee: 90000, grace_minutes: 0, capacity: 0, active: true, version: 1, minutes_to_full_rate: 188, updated_at: '' },
    { id: 'r2', vehicle_type: 'CAR', label: 'Carro', includes: 'Automóviles, camionetas y camperos', minute_rate: 150, full_rate: 32000, block_minutes: 720, monthly_fee: 180000, grace_minutes: 0, capacity: 40, active: true, version: 3, minutes_to_full_rate: 214, updated_at: '' },
  ];
  it('lists categories with derived values and lets administrators edit them with numbers', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => url.endsWith('/auth/csrf') ? json({ token: 't', headerName: 'X-XSRF-TOKEN' }) : init?.method === 'PUT' ? json(rates[1]) : json(rates));
    vi.stubGlobal('fetch', fetchMock);
    wrap(<VehicleRates role="ADMIN" notify={() => {}} />);
    expect(await screen.findByText('Automóviles, camionetas y camperos')).toBeInTheDocument();
    expect(screen.getByText('3 h 34 min')).toBeInTheDocument();
    expect(screen.getByText('Sin configurar')).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: /Editar/ })[1]);
    fireEvent.change(screen.getByLabelText(/Capacidad de estacionamiento/), { target: { value: '45' } });
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(c => (c[1] as RequestInit | undefined)?.method === 'PUT')).toBe(true));
    const put = fetchMock.mock.calls.find(c => (c[1] as RequestInit | undefined)?.method === 'PUT') as unknown as [string, RequestInit];
    expect(put[0]).toContain('/vehicle-rates/CAR');
    expect(JSON.parse(String(put[1].body))).toMatchObject({ minuteRate: 150, fullRate: 32000, blockMinutes: 720, capacity: 45, active: true });
  });

  it('is read-only for operators', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(rates)));
    wrap(<VehicleRates role="OPERATOR" notify={() => {}} />);
    expect(await screen.findByText('Carro')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Editar/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Historial/ })).not.toBeInTheDocument();
  });
});
