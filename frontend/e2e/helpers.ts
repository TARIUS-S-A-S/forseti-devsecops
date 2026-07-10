import { type Page, expect } from '@playwright/test'

const MAILPIT_HTTP = process.env.MAILPIT_URL ?? 'http://localhost:8025'

interface MailpitMessage {
  ID: string
  Subject: string
  From: { Address: string }
  To: Array<{ Address: string }>
}

interface MailpitDetail {
  HTML?: string
  Text?: string
}

/** Crea un email único por test para evitar colisiones. */
export function emailDelTest(prefix = 'e2e'): string {
  return `${prefix}+${Date.now()}-${Math.floor(Math.random() * 1e6)}@forseti.local`
}

/** Espera a que llegue un correo al destinatario y devuelve su contenido. */
export async function esperarEmail(destinatario: string, asuntoIncluye?: string,
                                     timeoutMs = 15000): Promise<MailpitDetail> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const resp = await fetch(`${MAILPIT_HTTP}/api/v1/messages?limit=50`)
    if (resp.ok) {
      const lista = (await resp.json()) as { messages: MailpitMessage[] }
      const match = lista.messages.find(m =>
        m.To?.some(t => t.Address.toLowerCase() === destinatario.toLowerCase()) &&
        (!asuntoIncluye || m.Subject.toLowerCase().includes(asuntoIncluye.toLowerCase()))
      )
      if (match) {
        const detail = await fetch(`${MAILPIT_HTTP}/api/v1/message/${match.ID}`)
        return (await detail.json()) as MailpitDetail
      }
    }
    await new Promise(r => setTimeout(r, 500))
  }
  throw new Error(`No llegó correo a ${destinatario} (asunto: ${asuntoIncluye}) en ${timeoutMs}ms`)
}

/** Extrae el token de verificación o recovery de un correo. */
export function tokenDelEmail(html: string): string {
  // Forseti pone un link tipo https://.../verificar-email?token=XYZ — extraemos XYZ
  const m = html.match(/token=([A-Za-z0-9_-]+)/)
  if (!m) throw new Error('No encontré el token en el correo')
  return m[1]
}

/** Loguea con email/password y opcionalmente OTP. */
export async function loginUI(page: Page, email: string, password: string, otp?: string) {
  await page.goto('/login')
  await page.getByLabel(/email/i).fill(email)
  await page.getByLabel(/contraseña/i).fill(password)
  if (otp) {
    await page.getByLabel(/código/i).fill(otp)
  }
  await page.getByRole('button', { name: /iniciar sesión|ingresar/i }).click()
}

/** Helper de smoke: verifica que estamos en la app autenticada. */
export async function asumirAutenticado(page: Page) {
  await expect(page).toHaveURL(/\/app/)
}
