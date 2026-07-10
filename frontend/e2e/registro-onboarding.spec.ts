import { expect, test } from '@playwright/test'
import { emailDelTest, esperarEmail, tokenDelEmail } from './helpers'

const PASSWORD = 'SuperSegura123!'

/**
 * Flujo completo: registro → verifica email vía Mailpit → login → onboarding empresa → dashboard.
 */
test('registro → email verificado → login → alta empresa → dashboard', async ({ page }) => {
  const email = emailDelTest('reg')

  // 1. Registro
  await page.goto('/registro')
  await page.getByLabel(/nombre/i).fill('Usuario E2E')
  await page.getByLabel(/email/i).fill(email)
  await page.getByLabel(/contraseña/i).fill(PASSWORD)
  await page.getByRole('button', { name: /crear cuenta/i }).click()
  await expect(page.getByText(/revisá tu email|cuenta creada/i)).toBeVisible()

  // 2. Esperar correo y extraer token
  const detalle = await esperarEmail(email, 'verificá')
  const token = tokenDelEmail(detalle.HTML ?? detalle.Text ?? '')

  // 3. Verificar email vía URL
  await page.goto(`/verificar-email?token=${token}`)
  await expect(page.getByText(/verificad/i)).toBeVisible()

  // 4. Login
  await page.goto('/login')
  await page.getByLabel(/email/i).fill(email)
  await page.getByLabel(/contraseña/i).fill(PASSWORD)
  await page.getByRole('button', { name: /iniciar sesión|ingresar/i }).click()

  // 5. Como no tiene empresas, debe llegar a alta empresa
  await expect(page).toHaveURL(/\/app\/empresas\/nueva/)

  // 6. Alta empresa
  await page.getByLabel(/^ruc$/i).fill('0992345675001')
  await page.getByLabel(/razón social/i).fill('Empresa E2E S.A.S.')
  await page.getByRole('button', { name: /crear empresa/i }).click()

  // 7. Redirige a carga de certificado (no lo subimos en e2e — flow alterno)
  await expect(page).toHaveURL(/\/certificado/)

  // 8. Volver al dashboard manualmente
  await page.goto('/app')
  await expect(page.getByText('Empresa E2E S.A.S.')).toBeVisible()
})
