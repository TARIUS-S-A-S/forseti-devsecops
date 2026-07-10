import { expect, test } from '@playwright/test'
import { emailDelTest, esperarEmail, tokenDelEmail } from './helpers'

const PASSWORD = 'SuperSegura123!'
const PASSWORD_MALA = 'PasswordIncorrecta!'

/**
 * Verifica que el lockout aplica: 5 intentos fallidos → bloqueo temporal.
 * Requisitos: backend con Sprint 1 auth completo + rate limiting + Mailpit.
 */
test('lockout: 5 intentos fallidos bloquean la cuenta', async ({ page }) => {
  // Crear cuenta verificada
  const email = emailDelTest('lock')
  await page.goto('/registro')
  await page.getByLabel(/nombre/i).fill('Lockout E2E')
  await page.getByLabel(/email/i).fill(email)
  await page.getByLabel(/contraseña/i).fill(PASSWORD)
  await page.getByRole('button', { name: /crear cuenta/i }).click()

  const detalle = await esperarEmail(email, 'verificá')
  const token = tokenDelEmail(detalle.HTML ?? detalle.Text ?? '')
  await page.goto(`/verificar-email?token=${token}`)

  // 5 intentos fallidos
  for (let i = 0; i < 5; i++) {
    await page.goto('/login')
    await page.getByLabel(/email/i).fill(email)
    await page.getByLabel(/contraseña/i).fill(PASSWORD_MALA)
    await page.getByRole('button', { name: /iniciar sesión|ingresar/i }).click()
    await expect(page.getByText(/incorrectos|inválidos/i)).toBeVisible({ timeout: 5000 })
  }

  // El 6to intento debe ser bloqueado por lockout
  await page.goto('/login')
  await page.getByLabel(/email/i).fill(email)
  await page.getByLabel(/contraseña/i).fill(PASSWORD)
  await page.getByRole('button', { name: /iniciar sesión|ingresar/i }).click()
  await expect(page.getByText(/bloqueada|esperá/i)).toBeVisible({ timeout: 5000 })
})
