import { api } from './client'

export interface FlujoCajaResponse {
  desde: string
  hasta: string
  totalIngresosCobrados: number
  totalIngresosPendientes: number
  totalEgresosPagados: number
  totalEgresosPendientes: number
  saldoCobradoMenosPagado: number
}

export const reportesApi = {
  async flujoCaja(desde?: string, hasta?: string): Promise<FlujoCajaResponse> {
    const params: Record<string, string> = {}
    if (desde) params.desde = desde
    if (hasta) params.hasta = hasta
    return (await api.get<FlujoCajaResponse>('/v1/reportes/flujo-caja', { params })).data
  },

  /** Devuelve un Blob CSV listo para download. La cookie de sesión va por withCredentials. */
  async descargarCsvCompras(desde?: string, hasta?: string): Promise<Blob> {
    const params: Record<string, string> = {}
    if (desde) params.desde = desde
    if (hasta) params.hasta = hasta
    const resp = await api.get('/v1/reportes/compras.csv', {
      params,
      responseType: 'blob',
    })
    return resp.data as Blob
  },

  async descargarCsvVentas(desde?: string, hasta?: string): Promise<Blob> {
    const params: Record<string, string> = {}
    if (desde) params.desde = desde
    if (hasta) params.hasta = hasta
    const resp = await api.get('/v1/reportes/ventas.csv', {
      params,
      responseType: 'blob',
    })
    return resp.data as Blob
  },
}

/** Dispara la descarga de un Blob como archivo. */
export function descargarBlob(blob: Blob, nombreArchivo: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = nombreArchivo
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
