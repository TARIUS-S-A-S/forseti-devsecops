import { api } from './client'

export type TipoIdReceptor = '04' | '05' | '06' | '07' | '08'
export type CodigoIva = 'IVA_0' | 'IVA_15' | 'NO_OBJETO' | 'EXENTO'
export type FormaPago = '01' | '15' | '16' | '17' | '18' | '19' | '20' | '21'
export type Ambiente = 'PRUEBAS' | 'PRODUCCION'
export type EstadoComprobante =
  | 'BORRADOR' | 'FIRMADA' | 'ENVIADA' | 'EN_PROCESO'
  | 'AUTORIZADA' | 'DEVUELTA' | 'NO_AUTORIZADA' | 'ABANDONADA'

export interface ReceptorRequest {
  tipoId: TipoIdReceptor
  identificacion: string
  razonSocial: string
  direccion?: string
  email?: string
  telefono?: string
}

export interface ItemRequest {
  codigoPrincipal: string
  codigoAuxiliar?: string
  descripcion: string
  cantidad: number | string
  precioUnitario: number | string
  descuento?: number | string
  codigoIva: CodigoIva
}

export interface EmitirFacturaRequest {
  establecimientoId: string
  puntoEmisionId: string
  fechaEmision?: string  // ISO date — null = hoy
  receptor: ReceptorRequest
  items: ItemRequest[]
  formaPago?: FormaPago
  plazoDias?: number
}

export interface EmitirNotaCreditoRequest {
  establecimientoId: string
  puntoEmisionId: string
  fechaEmision?: string
  receptor: ReceptorRequest
  items: ItemRequest[]
  docModificadoTipo: '01' | '03' | '04' | '05'  // codDoc, típicamente '01' factura
  docModificadoNumero: string                    // "001-001-000000001"
  docModificadoFecha: string                     // ISO date del doc original
  motivo: string                                 // por qué se emite la NC
}

export interface ReceptorResponse {
  tipoId: string
  identificacion: string
  razonSocial: string
  direccion: string | null
  email: string | null
  telefono: string | null
}

export interface TotalesResponse {
  subtotalSinImpuestos: string
  totalDescuento: string
  totalIva: string
  importeTotal: string
  moneda: string
}

export interface ComprobanteResponse {
  id: string
  establecimientoId: string
  puntoEmisionId: string
  tipoComprobante: string
  ambiente: Ambiente
  numeroComprobante: string
  claveAcceso: string
  fechaEmision: string
  estado: EstadoComprobante
  receptor: ReceptorResponse
  totales: TotalesResponse
  formaPago: string
  plazoDias: number
  numeroAutorizacion: string | null
  fechaAutorizacion: string | null
  mensajeSri: string | null
  codigoErrorSri: string | null
  intentosEnvio: number
  creadoAt: string
}

export interface DetalleResponse {
  orden: number
  codigoPrincipal: string
  codigoAuxiliar: string | null
  descripcion: string
  cantidad: string
  precioUnitario: string
  descuento: string
  precioTotalSinImpuesto: string
  codigoIva: string
  tarifa: string
  baseImponible: string
  valorImpuesto: string
}

export interface EventoResponse {
  cuando: string
  estadoAnterior: string | null
  estadoNuevo: string
  mensaje: string | null
}

export interface ComprobanteDetalladoResponse {
  cabecera: ComprobanteResponse
  detalles: DetalleResponse[]
  historia: EventoResponse[]
}

export const emisionApi = {
  async emitirFactura(req: EmitirFacturaRequest, ambiente: Ambiente = 'PRUEBAS'): Promise<ComprobanteResponse> {
    return (await api.post<ComprobanteResponse>(
      '/v1/comprobantes/factura',
      req,
      { params: { ambiente } }
    )).data
  },

  async emitirNotaCredito(req: EmitirNotaCreditoRequest, ambiente: Ambiente = 'PRUEBAS'): Promise<ComprobanteResponse> {
    return (await api.post<ComprobanteResponse>(
      '/v1/comprobantes/nota-credito',
      req,
      { params: { ambiente } }
    )).data
  },

  async listar(estado?: EstadoComprobante): Promise<ComprobanteResponse[]> {
    return (await api.get<ComprobanteResponse[]>(
      '/v1/comprobantes',
      { params: estado ? { estado } : {} }
    )).data
  },

  async obtener(id: string): Promise<ComprobanteDetalladoResponse> {
    return (await api.get<ComprobanteDetalladoResponse>(`/v1/comprobantes/${id}`)).data
  },

  async cancelar(id: string, motivo?: string): Promise<ComprobanteResponse> {
    return (await api.post<ComprobanteResponse>(
      `/v1/comprobantes/${id}/cancelar`,
      motivo ? { motivo } : {}
    )).data
  },
}
