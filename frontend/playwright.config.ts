import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright config — tests E2E para Forseti.
 *
 * Requisitos para correr localmente:
 *   1. Backend Spring Boot corriendo en http://localhost:8080 (con perfil local + Postgres + Mailpit)
 *   2. Frontend Vite en http://localhost:5173 (npm run dev)
 *   3. Browsers Playwright instalados: `npx playwright install chromium`
 *
 * Ejecutar: `npm run test:e2e`
 *
 * Nota: los tests asumen que Mailpit (smtp 1025, http 8025) está disponible para leer correos de verificación.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,            // los tests comparten DB local; correrlos serial evita carreras
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,                       // serial por la razón anterior
  reporter: process.env.CI ? 'github' : 'list',

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    ignoreHTTPSErrors: true,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  // No arrancamos webServer automático: en local se asume `npm run dev` aparte y el backend ya corriendo
  // (mantiene el ciclo más rápido y deja al dev controlar el estado de la DB).
})
