import { api } from './client'

export interface MeResponse {
  id: string
  email: string | null
  username: string | null
  nombre: string
  emailVerificado: boolean
  tieneTotp: boolean
  totpLoginRequired: boolean
  debeCambiarPassword: boolean
  empresas: Array<{ id: string; razonSocial: string; rol: string }>
}

export interface LoginResponse {
  estado: 'EXITOSO' | 'REQUIERE_2FA'
  me: MeResponse | null
}

export const authApi = {
  async register(email: string, nombre: string, password: string, username?: string) {
    return (await api.post('/v1/auth/register', { email, nombre, password, username })).data
  },

  async verifyEmail(token: string) {
    return (await api.post('/v1/auth/verify-email', { token })).data
  },

  /** El campo "email" del backend acepta email o username; pasamos el identificador como está. */
  async login(identificador: string, password: string, otp?: string): Promise<LoginResponse> {
    return (await api.post<LoginResponse>('/v1/auth/login', { email: identificador, password, otp })).data
  },

  async cambiarPassword(passwordActual: string, passwordNueva: string): Promise<void> {
    await api.post('/v1/auth/cambiar-password', { passwordActual, passwordNueva })
  },

  async logout() {
    return (await api.post('/v1/auth/logout')).data
  },

  async recovery(email: string) {
    return (await api.post('/v1/auth/recovery', { email })).data
  },

  async resetPassword(token: string, password: string) {
    return (await api.post('/v1/auth/reset-password', { token, password })).data
  },

  async me(): Promise<MeResponse> {
    return (await api.get<MeResponse>('/v1/auth/me')).data
  },

  // 2FA
  async setup2FA(): Promise<{ secret: string; otpAuthUri: string; qrPngBase64: string }> {
    return (await api.post('/v1/auth/2fa/setup')).data
  },

  async confirm2FA(secret: string, code: string) {
    return (await api.post('/v1/auth/2fa/confirm', { secret, code })).data
  },

  async disable2FA() {
    return (await api.delete('/v1/auth/2fa')).data
  },

  /** Pausa o reanuda el pedido de 2FA al loguearse SIN desactivarlo (mantiene el secret). */
  async setTotpLoginRequired(required: boolean) {
    return (await api.patch('/v1/auth/2fa/login-required', { required })).data
  },
}
