/**
 * Role sweep: logs in with each seeded account and visits every menu entry that role can see,
 * failing on visible errors, uncaught page errors or unexpected API failures.
 * Requires SP_CREDENTIALS=<path to credentials JSON written by infrastructure/scripts/seed/seed_local.py>.
 */
import { expect, test } from '@playwright/test';
import { accounts, login } from './support';

for (const role of ['SUPERADMIN', 'ADMIN', 'SUPERVISOR', 'OPERATOR', 'CUSTOMER']) {
  test(`${role}: todas las pantallas del menú cargan sin errores`, async ({ page }) => {
    test.skip(!accounts[role], 'Defina SP_CREDENTIALS con las cuentas sembradas');
    const apiFailures: string[] = [];
    const pageErrors: string[] = [];
    page.on('pageerror', e => pageErrors.push(e.message));
    page.on('response', r => {
      if (r.url().includes('/api/v1/') && r.status() >= 400 && !r.url().endsWith('/auth/me')) apiFailures.push(`${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`);
    });

    await login(page, accounts[role]);
    await expect(page.getByText(/requiere verificación en dos pasos/i)).toHaveCount(0);

    const links = await page.locator('nav[aria-label="Navegación principal"] a').evaluateAll(as => as.map(a => ({ href: a.getAttribute('href')!, text: a.textContent!.trim() })));
    expect(links.length).toBeGreaterThan(3);
    // With two-step verification switched off on the server, its screen is not offered.
    const me = await (await page.request.get('/api/v1/auth/me')).json();
    if (me.mfaAvailable === false) expect(links.map(l => l.href)).not.toContain('/security');
    const report: string[] = [];
    for (const link of links) {
      const before = apiFailures.length;
      await page.locator(`nav[aria-label="Navegación principal"] a[href="${link.href}"]`).click();
      await page.waitForLoadState('networkidle');
      await expect(page.locator('main h1').first()).toBeVisible();
      const alerts = await page.locator('main [role="alert"].error').allTextContents();
      const failures = apiFailures.slice(before);
      if (alerts.length || failures.length) report.push(`${link.text} (${link.href}): ${[...alerts, ...failures].join(' | ')}`);
    }
    console.log(`[${role}] ${links.length} pantallas: ${links.map(l => l.text).join(', ')}`);
    expect(pageErrors, 'errores de JavaScript').toEqual([]);
    expect(report, 'pantallas con error').toEqual([]);
  });
}
