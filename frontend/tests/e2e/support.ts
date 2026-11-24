/** Shared helpers for E2E specs: seeded accounts (SP_CREDENTIALS), TOTP and login. */
import { createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { expect, type Page } from '@playwright/test';

export type Account = { email: string; password: string; role: string; mfa_secret?: string };
const file = process.env.SP_CREDENTIALS;
export const accounts: Record<string, Account> = file ? JSON.parse(readFileSync(file, 'utf-8')).accounts : {};

function base32(secret: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of secret.replace(/=+$/, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}

export function totp(secret: string, step = Math.floor(Date.now() / 30000)): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const h = createHmac('sha1', base32(secret)).update(counter).digest();
  const o = h[h.length - 1] & 15;
  return String(((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000)).padStart(6, '0');
}

export async function login(page: Page, a: Account) {
  await page.goto('/');
  await page.getByLabel('Correo electrónico').fill(a.email);
  await page.getByLabel('Contraseña').fill(a.password);
  await page.getByRole('button', { name: /ingresar a mi cuenta/i }).click();
  if (a.mfa_secret) {
    await expect(page.getByRole('heading', { name: /código de seguridad/i })).toBeVisible();
    // A code already used (e.g. at enrolment) is rejected as replay: retry with the next time step.
    for (let attempt = 0; attempt < 3; attempt++) {
      await page.getByLabel(/^código$/i).fill(totp(a.mfa_secret, Math.floor(Date.now() / 30000) + attempt));
      await page.getByRole('button', { name: /verificar/i }).click();
      // Wait for either outcome: the app shell (accepted) or an error message (rejected, e.g. replayed code).
      const shell = page.locator('aside.sidebar');
      await expect(shell.or(page.locator('[role="alert"].error'))).toBeVisible();
      if (await shell.isVisible()) break;
    }
  }
  await expect(page.locator('aside.sidebar')).toBeVisible();
}

