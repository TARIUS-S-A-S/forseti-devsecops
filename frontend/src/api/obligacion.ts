import { api } from './client'

export interface ObligacionCatalogo {
  codigo: string
  nombre: string
  descripcion: string | null
  categoria: 'SRI_DECLARACION' | 'SRI_ANEXO' | 'SUPERCIA' | 'MUNICIPIO' | 'IESS_MDT' | 'INTERNA'
  periodicidad: 'MENSUAL' | 'SEMESTRAL' | 'ANUAL' | 'UNICA' | 'EVENTUAL'
  reglaFecha: string | null
  aplicaSi: Record<string, unknown>
  bloqueante: boolean
  alertaDias: number[] | null
  orden: number
}

export interface ObligacionEmpresa {
  id: string
  empresaId: string
  obligacionCodigo: string
  activa: boolean
  config: Record<string, unknown>
  activadaAt: string
}

export const obligacionApi = {
  async catalogo(): Promise<ObligacionCatalogo[]> {
    return (await api.get<ObligacionCatalogo[]>('/v1/obligaciones/catalogo')).data
  },

  async activadas(empresaId: string): Promise<ObligacionEmpresa[]> {
    return (await api.get<ObligacionEmpresa[]>(`/v1/empresas/${empresaId}/obligaciones`)).data
  },

  async sugeridas(empresaId: string): Promise<ObligacionCatalogo[]> {
    return (await api.get<ObligacionCatalogo[]>(`/v1/empresas/${empresaId}/obligaciones/sugeridas`)).data
  },

  async activar(empresaId: string, codigo: string): Promise<ObligacionEmpresa> {
    return (await api.post<ObligacionEmpresa>(`/v1/empresas/${empresaId}/obligaciones/${codigo}`)).data
  },

  async desactivar(empresaId: string, codigo: string): Promise<void> {
    await api.delete(`/v1/empresas/${empresaId}/obligaciones/${codigo}`)
  },
}
