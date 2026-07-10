import { api } from './client'

export type TipoDocumentoCompra =
  | 'FACTURA'
  | 'NOTA_CREDITO'
  | 'NOTA_DEBITO'
  | 'LIQUIDACION_COMPRA'
  | 'RECIBO'
  | 'OTRO'

export type EstadoPagoCompra = 'PENDIENTE' | 'PAGADO' | 'PARCIAL'
export type OrigenCompra = 'MANUAL' | 'XML'

export interface CompraResponse {
  id: string
  fechaEmision: string
  proveedorTipoId: string
  proveedorIdentificacion: string
  proveedorRazonSocial: string
  tipoDocumento: TipoDocumentoCompra
  numeroDocumento: string
  claveAcceso: string | null
  concepto: string
  categoriaId: string | null
  categoriaNombre: string | null
  baseIva15: number
  baseIva0: number
  baseNoObjeto: number
  baseExento: number
  valorIva15: number
  retencionIr: number
  retencionIva: number
  total: number
  deducible: boolean
  estadoPago: EstadoPagoCompra
  fechaPago: string | null
  formaPago: string | null
  origen: OrigenCompra
  anulada: boolean
  anuladaAt: string | null
  motivoAnulacion: string | null
  adjuntosCount: number
  creadoAt: string
}

export interface CrearCompraRequest {
  fechaEmision: string
  proveedorTipoId: '04' | '05' | '06' | '08'
  proveedorIdentificacion: string
  proveedorRazonSocial: string
  tipoDocumento: TipoDocumentoCompra
  numeroDocumento: string
  concepto: string
  categoriaId?: string | null
  baseIva15: number
  baseIva0: number
  baseNoObjeto: number
  baseExento: number
  valorIva15: number
  retencionIr: number
  retencionIva: number
  total: number
  deducible?: boolean
  formaPago?: string
  fechaPago?: string | null
}

export interface CategoriaResponse {
  id: string
  codigo: string
  nombre: string
  descripcion: string | null
  orden: number
}

export interface AdjuntoResponse {
  id: string
  nombreOriginal: string
  mimeTypeReal: string
  tamanoBytes: number
  sha256: string
  creadoAt: string
}

export const comprasApi = {
  async listar(desde?: string, hasta?: string): Promise<CompraResponse[]> {
    const params: Record<string, string> = {}
    if (desde) params.desde = desde
    if (hasta) params.hasta = hasta
    return (await api.get<CompraResponse[]>('/v1/compras', { params })).data
  },

  async obtener(id: string): Promise<CompraResponse> {
    return (await api.get<CompraResponse>(`/v1/compras/${id}`)).data
  },

  async crearManual(req: CrearCompraRequest): Promise<CompraResponse> {
    return (await api.post<CompraResponse>('/v1/compras', req)).data
  },

  async crearDesdeXml(xml: File, categoriaId?: string): Promise<CompraResponse> {
    const fd = new FormData()
    fd.append('xml', xml)
    if (categoriaId) fd.append('categoriaId', categoriaId)
    const resp = await api.post<CompraResponse>('/v1/compras/xml', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    return resp.data
  },

  async anular(id: string, motivo: string): Promise<CompraResponse> {
    return (await api.post<CompraResponse>(`/v1/compras/${id}/anular`, { motivo })).data
  },

  async marcarPagado(id: string, fechaPago: string, formaPago: string): Promise<CompraResponse> {
    return (await api.post<CompraResponse>(`/v1/compras/${id}/marcar-pagado`,
      { fechaPago, formaPago })).data
  },

  async subirAdjunto(compraId: string, file: File): Promise<AdjuntoResponse> {
    const fd = new FormData()
    fd.append('file', file)
    return (await api.post<AdjuntoResponse>(`/v1/compras/${compraId}/adjuntos`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })).data
  },

  async listarAdjuntos(compraId: string): Promise<AdjuntoResponse[]> {
    return (await api.get<AdjuntoResponse[]>(`/v1/compras/${compraId}/adjuntos`)).data
  },

  urlAdjunto(compraId: string, adjuntoId: string): string {
    return `/api/v1/compras/${compraId}/adjuntos/${adjuntoId}`
  },

  async listarCategorias(): Promise<CategoriaResponse[]> {
    return (await api.get<CategoriaResponse[]>('/v1/compras/categorias')).data
  },
}
