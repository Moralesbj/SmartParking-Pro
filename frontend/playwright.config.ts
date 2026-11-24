import { defineConfig } from '@playwright/test';

// Runs against an already running stack (local dev or Compose). Set E2E_BASE_URL to override.
export default defineConfig({
  testDir: './tests/e2e',
  timeout: 120_000,
  workers: 1,
  reporter: [['list']],
  // E2E_CHANNEL=msedge (or chrome) uses an installed browser instead of Playwright's downloaded Chromium.
  use: { baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173', channel: process.env.E2E_CHANNEL || undefined, locale: 'es-CO', timezoneId: 'America/Bogota', trace: 'off' },
});
