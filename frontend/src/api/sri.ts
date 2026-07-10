import { api } from './client'

export interface EstadoAmbiente {
  ambiente: 'PRUEBAS' | 'PRODUCCION'
  estado: 'ARRIBA' | 'DEGRADADO' | 'CAIDO' | 'DESCONOCIDO'
  latenciaMs: number | null
  mensaje: string | null
  ultimoCheck: string | null
  circuitBreaker: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  fallosConsecutivos: number
}

export interface EstadoSriResponse {
  pruebas: EstadoAmbiente
  produccion: EstadoAmbiente
  consultadoEn: string
}

export interface ColaSriResponse {
  porEstado: Record<string, number>
  antiguedadMaxPendienteSeg: number | null
  total24h: number
  autorizados24h: number
  tasaExito24h: number | null
  tiempoPromAutorizacionSeg: number | null
  consultadoEn: string
}

export const sriApi = {
  /** Estado del WS SRI (alimentado por health check cada 5min en backend). Público, sin auth. */
  async estado(): Promise<EstadoSriResponse> {
    const resp = await api.get<EstadoSriResponse>('/v1/sri/estado')
    return resp.data
  },

  /** Métricas de la cola de comprobantes de la empresa activa. Requiere auth + empresa activa. */
  async cola(): Promise<ColaSriResponse | null> {
    const resp = await api.get<ColaSriResponse>('/v1/sri/cola', {
      validateStatus: (s) => s === 200 || s === 204,
    })
    return resp.status === 204 ? null : resp.data
  },
}
