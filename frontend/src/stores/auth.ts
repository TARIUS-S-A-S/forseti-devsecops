import axios from 'axios'
import { defineStore } from 'pinia'
import { authApi, type MeResponse } from '@/api/auth'
import { empresaApi } from '@/api/empresa'

interface AuthState {
  user: MeResponse | null
  empresaActivaId: string | null
  ready: boolean
  lastError: string | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    user: null,
    empresaActivaId: null,
    ready: false,
    lastError: null,
  }),
  getters: {
    isAuthenticated: (state): boolean => !!state.user,
    empresaActiva: (state) => state.user?.empresas.find(e => e.id === state.empresaActivaId) ?? null,
  },
  actions: {
    async fetchUser(): Promise<void> {
      try {
        const me = await authApi.me()
        this.user = me
        this.empresaActivaId = me.empresas[0]?.id ?? null
        this.lastError = null
      } catch (e) {
        if (axios.isAxiosError(e) && e.response?.status === 401) {
          this.user = null
          this.empresaActivaId = null
        } else {
          this.lastError = (e as Error).message
          console.error('[auth] fetchUser error', e)
        }
      } finally {
        this.ready = true
      }
    },

    async login(identificador: string, password: string, otp?: string): Promise<{ requiere2FA: boolean }> {
      const resp = await authApi.login(identificador, password, otp)
      if (resp.estado === 'REQUIERE_2FA') {
        return { requiere2FA: true }
      }
      this.user = resp.me
      this.empresaActivaId = resp.me?.empresas[0]?.id ?? null
      return { requiere2FA: false }
    },

    async logout(): Promise<void> {
      try {
        await authApi.logout()
      } finally {
        this.clearLocal()
      }
    },

    clearLocal(): void {
      this.user = null
      this.empresaActivaId = null
      this.lastError = null
    },

    setEmpresaActiva(empresaId: string) {
      if (this.user?.empresas.some(e => e.id === empresaId)) {
        this.empresaActivaId = empresaId
      }
    },

    /** Cambia la empresa activa en el backend (cookie session) + refresca local. */
    async cambiarEmpresaActiva(empresaId: string): Promise<void> {
      await empresaApi.seleccionar(empresaId)
      this.setEmpresaActiva(empresaId)
    },

    /** Refresca los datos del usuario tras alta de empresa, cambio de perfil, etc. */
    async refrescar(): Promise<void> {
      const me = await authApi.me()
      this.user = me
      if (!this.empresaActivaId || !me.empresas.some(e => e.id === this.empresaActivaId)) {
        this.empresaActivaId = me.empresas[0]?.id ?? null
      }
    },
  },
})
