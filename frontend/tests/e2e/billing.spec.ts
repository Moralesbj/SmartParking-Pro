/**
 * Operator flow in the browser: register a car, admit it without a space, land on its ticket (code + QR),
 * liquidate, collect in cash and release it. Also checks the per-category rates page.
 * Requires SP_CREDENTIALS (see roles.spec.ts). Creates one test vehicle and leaves its stay closed.
 */
import { expect, test } from '@playwright/test';
import { accounts, login } from './support';

const letters = 'BCDFGHJKLMNPRSTVWXYZ';
const randomPlate = () => Array.from({ length: 3 }, () => letters[Math.floor(Math.random() * letters.length)]).join('') + String(Math.floor(Math.random() * 900) + 100);

test('OPERATOR: entrada sin cupo, tiquete con QR, liquidación, cobro y salida', async ({ page }) => {
  test.skip(!accounts.OPERATOR, 'Defina SP_CREDENTIALS con las cuentas sembradas');
  const pageErrors: string[] = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  page.on('dialog', d => void d.accept());
  await login(page, accounts.OPERATOR);

  // Rates are visible to operators, read-only, with the derived "full rate reached at" time.
  await page.goto('/vehicle-rates');
  await expect(page.getByRole('heading', { name: 'Tarifas por vehículo' })).toBeVisible();
  const carRow = page.getByRole('row', { name: /Carro/ });
  await expect(carRow).toContainText('Automóviles, camionetas y camperos');
  await expect(carRow).toContainText('3 h 34 min');
  await expect(page.getByRole('button', { name: /Editar/ })).toHaveCount(0);

  // Own cash drawer (reused if already open from a previous run).
  await page.goto('/cash');
  await page.getByRole('button', { name: 'Abrir caja' }).click();
  await page.getByLabel(/Fondo inicial/).fill('0');
  await page.getByRole('button', { name: 'Guardar' }).click();
  const dialog = page.getByRole('dialog');
  // Either the drawer opens (dialog closes) or the server says one is already open (alert inside the dialog).
  await Promise.race([dialog.waitFor({ state: 'detached' }), dialog.getByRole('alert').waitFor()]);
  if (await dialog.getByRole('alert').isVisible()) await dialog.getByRole('button', { name: 'Cancelar' }).click();
  await expect(dialog).toHaveCount(0);

  const plate = randomPlate();
  await page.goto('/vehicles');
  await page.getByRole('button', { name: 'Agregar' }).click();
  await page.getByLabel(/^Placa/).fill(plate);
  await page.getByLabel(/^Tipo/).selectOption('CAR');
  await page.getByRole('button', { name: 'Guardar' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);

  await page.goto('/parking');
  await page.getByRole('button', { name: 'Registrar entrada' }).click();
  await page.getByLabel(/^Vehículo/).selectOption({ label: `${plate} · Carro` });
  await expect(page.getByLabel(/^Cupo \(opcional\)/)).toHaveValue('');
  await page.getByRole('button', { name: 'Guardar' }).click();

  // Check-in lands on the ticket: six-digit code, QR and the full liquidation.
  await expect(page).toHaveURL(/\/ticket-lookup\?q=\d{6}$/);
  const code = new URL(page.url()).searchParams.get('q')!;
  await expect(page.getByLabel('Código de seis dígitos', { exact: true })).toHaveText(code);
  await expect(page.getByAltText(/Código QR del tiquete/)).toHaveAttribute('src', /^data:image\/svg\+xml/);
  for (const label of ['Tarifa por minuto', 'Valor de la tarifa plena', 'Tarifas plenas alcanzadas', 'Saldo pendiente', 'Estado de la mensualidad'])
    await expect(page.getByText(label, { exact: true })).toBeVisible();
  await expect(page.locator('.fact', { hasText: 'Placa' }).first()).toContainText(plate);
  await expect(page.locator('.fact', { hasText: 'Tarifa por minuto' })).toContainText('150');

  // Plate search finds the same ticket.
  await page.getByLabel(/contenido del QR/).fill(plate.toLowerCase());
  await page.getByRole('button', { name: 'Consultar' }).click();
  await expect(page.getByLabel('Código de seis dígitos', { exact: true })).toHaveText(code);

  await page.getByRole('button', { name: 'Liquidar y cobrar' }).click();
  // A stay of under a minute may cost $0 (no payment form) or one started minute (payment form).
  const payment = page.getByRole('dialog');
  await Promise.race([payment.waitFor(), page.locator('.toast', { hasText: 'Sin saldo' }).waitFor()]);
  if (await payment.isVisible()) {
    await expect(payment.getByRole('heading', { name: /^Cobrar/ })).toBeVisible();
    await payment.getByRole('button', { name: 'Guardar' }).click();
    await expect(payment).toHaveCount(0);
  }
  await expect(page.locator('.fact', { hasText: 'Estado del tiquete' })).toContainText('Pagado');
  await expect(page.locator('.fact', { hasText: 'Saldo pendiente' })).toContainText('$');
  await page.getByRole('button', { name: 'Registrar salida' }).click();
  await expect(page.locator('.fact', { hasText: 'Estado del tiquete' })).toContainText('Cerrado');
  expect(pageErrors, 'errores de JavaScript').toEqual([]);
});
