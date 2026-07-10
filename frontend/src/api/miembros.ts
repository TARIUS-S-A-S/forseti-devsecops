import { api } from './client'

export type Rol = 'DUENO' | 'CONTADORA' | 'EMPLEADO' | 'ADMIN'

export interface Miembro {
  membershipId: string
  usuarioId: string
  nombre: string
  email: string | null
  username: string | null
  rol: Rol
  debeCambiarPassword: boolean
  ultimoLoginAt: string | null
}

export interface InvitacionView {
  id: string
  email: string
  nombreInvitado: string | null
  rol: Rol
  creadaAt: string
  expiraAt: string
}

export interface InvitarPorEmailResponse {
  invitacionId: string | null
  usuarioYaExistia: boolean
  mensaje: string
}

export interface CrearConUsernameResponse {
  usuarioId: string
  username: string
  passwordTemporal: string
  mensaje: string
}

export interface ResetPasswordResponse {
  passwordTemporal: string
  mensaje: string
}

export interface InvitacionPublicView {
  email: string
  nombreInvitado: string | null
  rol: Rol
  nombreEmpresa: string
  expiraAt: string
  noDisponible: boolean
  expirada: boolean
  yaAceptada: boolean
  cancelada: boolean
}

export const miembrosApi = {
  async listarMiembros(empresaId: string): Promise<Miembro[]> {
    return (await api.get<Miembro[]>(`/v1/empresas/${empresaId}/miembros`)).data
  },

  async listarInvitaciones(empresaId: string): Promise<InvitacionView[]> {
    return (await api.get<InvitacionView[]>(`/v1/empresas/${empresaId}/invitaciones`)).data
  },

  async invitarPorEmail(empresaId: string, email: string, nombre: string, rol: Rol): Promise<InvitarPorEmailResponse> {
    return (await api.post<InvitarPorEmailResponse>(`/v1/empresas/${empresaId}/miembros/email`, { email, nombre, rol })).data
  },

  async crearConUsername(empresaId: string, username: string, nombre: string, rol: Rol, passwordTemporal: string): Promise<CrearConUsernameResponse> {
    return (await api.post<CrearConUsernameResponse>(`/v1/empresas/${empresaId}/miembros/username`, {
      username, nombre, rol, passwordTemporal,
    })).data
  },

  async cambiarRol(empresaId: string, usuarioId: string, rol: Rol): Promise<void> {
    await api.patch(`/v1/empresas/${empresaId}/miembros/${usuarioId}/rol`, { rol })
  },

  async quitar(empresaId: string, usuarioId: string): Promise<void> {
    await api.delete(`/v1/empresas/${empresaId}/miembros/${usuarioId}`)
  },

  async resetPassword(empresaId: string, usuarioId: string): Promise<ResetPasswordResponse> {
    return (await api.post<ResetPasswordResponse>(`/v1/empresas/${empresaId}/miembros/${usuarioId}/reset-password`)).data
  },

  async cancelarInvitacion(empresaId: string, invitacionId: string): Promise<void> {
    await api.delete(`/v1/empresas/${empresaId}/invitaciones/${invitacionId}`)
  },

  async verInvitacionPublica(token: string): Promise<InvitacionPublicView> {
    return (await api.get<InvitacionPublicView>(`/v1/invitaciones/${token}`)).data
  },

  async aceptarInvitacion(token: string): Promise<{ empresaId: string; mensaje: string }> {
    return (await api.post<{ empresaId: string; mensaje: string }>(`/v1/invitaciones/${token}/aceptar`)).data
  },
}
