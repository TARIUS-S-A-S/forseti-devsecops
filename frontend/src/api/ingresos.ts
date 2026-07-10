import { api } from './client'

export type EstadoCobro = 'PENDIENTE' | 'COBRADO' | 'PARCIAL'

export interface IngresoManualResponse {
  id: string
  fechaEmision: string
  clienteIdentificacion: string | null
  clienteRazonSocial: string
  concepto: string
  baseIva15: number
  baseIva0: number
  valorIva15: number
  retencionRecibida: number
  total: number
  estadoCobro: EstadoCobro
  fechaCobro: string | null
  anulada: boolean
  anuladaAt: string | null
  motivoAnulacion: string | null
  creadoAt: string
}

export interface CrearIngresoManualRequest {
  fechaEmision: string
  clienteIdentificacion?: string
  clienteRazonSocial: string
  concepto: string
  baseIva15: number
  baseIva0: number
  valorIva15: number
  retencionRecibida: number
  total: number
  fechaCobro?: string | null
}

export const ingresosApi = {
  async listar(desde?: string, hasta?: string): Promise<IngresoManualResponse[]> {
    const params: Record<string, string> = {}
    if (desde) params.desde = desde
    if (hasta) params.hasta = hasta
    return (await api.get<IngresoManualResponse[]>('/v1/ingresos-manuales', { params })).data
  },

  async obtener(id: string): Promise<IngresoManualResponse> {
    return (await api.get<IngresoManualResponse>(`/v1/ingresos-manuales/${id}`)).data
  },

  async crear(req: CrearIngresoManualRequest): Promise<IngresoManualResponse> {
    return (await api.post<IngresoManualResponse>('/v1/ingresos-manuales', req)).data
  },

  async anular(id: string, motivo: string): Promise<IngresoManualResponse> {
    return (await api.post<IngresoManualResponse>(`/v1/ingresos-manuales/${id}/anular`,
      { motivo })).data
  },

  async marcarCobrado(id: string, fechaCobro: string): Promise<IngresoManualResponse> {
    return (await api.post<IngresoManualResponse>(`/v1/ingresos-manuales/${id}/marcar-cobrado`,
      { fechaCobro })).data
  },
}
