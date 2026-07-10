import { api } from './client'

export interface Empresa {
  id: string
  ruc: string
  razonSocial: string
  nombreComercial: string | null
  tipoContribuyente: 'PN' | 'SA' | 'SAS' | 'LTDA' | 'EP' | 'OTRO'
  regimenTributario: 'RIMPE_NP' | 'RIMPE_EMPRENDEDOR' | 'GENERAL'
  periodicidadIva: 'MENSUAL' | 'SEMESTRAL' | 'NO_APLICA'
  obligadoContabilidad: boolean
  agenteRetencion: boolean
  direccion: string | null
  ciudad: string | null
  provincia: string | null
  telefono: string | null
  email: string | null
  activa: boolean
  ambienteDefault: 'PRUEBAS' | 'PRODUCCION'
  creadaAt: string
}

export type TipoComprobante = 'FACTURA' | 'NOTA_CREDITO' | 'NOTA_DEBITO' | 'RETENCION' | 'GUIA_REMISION' | 'LIQUIDACION_COMPRA'

export interface SecuencialResponse {
  id: string
  puntoEmisionId: string
  tipoComprobante: TipoComprobante
  ambiente: 'PRUEBAS' | 'PRODUCCION'
  proximoNumero: number
}

export interface CrearEmpresaRequest {
  ruc: string
  razonSocial: string
  nombreComercial?: string
  tipoContribuyente: Empresa['tipoContribuyente']
  regimenTributario: Empresa['regimenTributario']
  periodicidadIva: Empresa['periodicidadIva']
  obligadoContabilidad: boolean
  agenteRetencion: boolean
  direccion?: string
  ciudad?: string
  provincia?: string
  telefono?: string
  email?: string
}

export interface PerfilTributario {
  id: string
  empresaId: string
  vigenteDesde: string
  vigenteHasta: string | null
  regimenTributario: Empresa['regimenTributario']
  periodicidadIva: Empresa['periodicidadIva']
  obligadoContabilidad: boolean
  agenteRetencion: boolean
  motivoCambio: string | null
  creadoAt: string
}

export interface ActualizarPerfilTributarioRequest {
  regimenTributario: Empresa['regimenTributario']
  periodicidadIva: Empresa['periodicidadIva']
  obligadoContabilidad: boolean
  agenteRetencion: boolean
  vigenteDesde?: string
  motivoCambio?: string
}

export interface Establecimiento {
  id: string
  codigo: string
  nombre: string | null
  direccion: string | null
  activo: boolean
}

export interface PuntoEmision {
  id: string
  establecimientoId: string
  codigo: string
  descripcion: string | null
  activo: boolean
}

export interface CertificadoView {
  id: string
  sujetoCn: string | null
  emisorCn: string | null
  numeroSerie: string | null
  vigenteDesde: string | null
  vigenteHasta: string
  activo: boolean
  cargadoAt: string
  diasParaCaducar: number
}

export const empresaApi = {
  async listarMias(): Promise<Empresa[]> {
    return (await api.get<Empresa[]>('/v1/empresas')).data
  },

  async crear(req: CrearEmpresaRequest): Promise<Empresa> {
    return (await api.post<Empresa>('/v1/empresas', req)).data
  },

  async obtener(id: string): Promise<Empresa> {
    return (await api.get<Empresa>(`/v1/empresas/${id}`)).data
  },

  async seleccionar(empresaId: string): Promise<void> {
    await api.post('/v1/empresas/seleccionar', { empresaId })
  },

  async perfilVigente(empresaId: string): Promise<PerfilTributario> {
    return (await api.get<PerfilTributario>(`/v1/empresas/${empresaId}/perfil-tributario`)).data
  },

  async actualizarPerfil(empresaId: string, req: ActualizarPerfilTributarioRequest): Promise<PerfilTributario> {
    return (await api.put<PerfilTributario>(`/v1/empresas/${empresaId}/perfil-tributario`, req)).data
  },

  async historialPerfil(empresaId: string): Promise<PerfilTributario[]> {
    return (await api.get<PerfilTributario[]>(`/v1/empresas/${empresaId}/perfil-tributario/historial`)).data
  },

  async listarEstablecimientos(empresaId: string): Promise<Establecimiento[]> {
    return (await api.get<Establecimiento[]>(`/v1/empresas/${empresaId}/establecimientos`)).data
  },

  async crearEstablecimiento(empresaId: string, codigo: string, nombre?: string, direccion?: string): Promise<Establecimiento> {
    return (await api.post<Establecimiento>(`/v1/empresas/${empresaId}/establecimientos`, { codigo, nombre, direccion })).data
  },

  async listarPuntos(empresaId: string, establecimientoId: string): Promise<PuntoEmision[]> {
    return (await api.get<PuntoEmision[]>(`/v1/empresas/${empresaId}/establecimientos/${establecimientoId}/puntos-emision`)).data
  },

  async crearPunto(empresaId: string, establecimientoId: string, codigo: string, descripcion?: string): Promise<PuntoEmision> {
    return (await api.post<PuntoEmision>(`/v1/empresas/${empresaId}/establecimientos/${establecimientoId}/puntos-emision`, { codigo, descripcion })).data
  },

  async certificadoActivo(empresaId: string): Promise<CertificadoView | null> {
    const resp = await api.get<CertificadoView>(`/v1/empresas/${empresaId}/certificado`, {
      validateStatus: (s) => s === 200 || s === 204,
    })
    if (resp.status === 204) return null
    return resp.data
  },

  async cargarCertificado(empresaId: string, archivo: File, password: string): Promise<CertificadoView> {
    const form = new FormData()
    form.append('p12', archivo)
    form.append('password', password)
    const resp = await api.post<CertificadoView>(`/v1/empresas/${empresaId}/certificado`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    return resp.data
  },

  /** Reemplaza el cert activo. Internamente desactiva el anterior y carga el nuevo. */
  async reemplazarCertificado(empresaId: string, archivo: File, password: string): Promise<CertificadoView> {
    const form = new FormData()
    form.append('p12', archivo)
    form.append('password', password)
    const resp = await api.put<CertificadoView>(`/v1/empresas/${empresaId}/certificado`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    return resp.data
  },

  /**
   * Desactiva el cert activo sin cargar uno nuevo. Después de esto la empresa no puede
   * emitir facturas hasta cargar uno. NO borra el registro (preserva trazabilidad de
   * facturas pasadas). Idempotente.
   */
  async desactivarCertificado(empresaId: string): Promise<void> {
    await api.delete(`/v1/empresas/${empresaId}/certificado`)
  },

  /**
   * Cambia el ambiente SRI default de la empresa. PRODUCCION requiere validaciones
   * (cert activo, perfil vigente, secuencial PRODUCCION). Volver a PRUEBAS siempre se puede.
   */
  async cambiarAmbienteDefault(empresaId: string,
                                ambiente: 'PRUEBAS' | 'PRODUCCION'): Promise<Empresa> {
    const resp = await api.put<Empresa>(`/v1/empresas/${empresaId}/ambiente`, { ambiente })
    return resp.data
  },

  /** Archiva (soft-delete) la empresa. Solo el DUEÑO. Preserva trazabilidad. */
  async archivarEmpresa(empresaId: string): Promise<Empresa> {
    const resp = await api.delete<Empresa>(`/v1/empresas/${empresaId}`)
    return resp.data
  },

  async reactivarEmpresa(empresaId: string): Promise<Empresa> {
    const resp = await api.post<Empresa>(`/v1/empresas/${empresaId}/reactivar`)
    return resp.data
  },

  /** Lista secuenciales del punto de emisión con su próximo número por tipo+ambiente. */
  async listarSecuenciales(empresaId: string, establecimientoId: string, puntoEmisionId: string): Promise<SecuencialResponse[]> {
    const resp = await api.get<SecuencialResponse[]>(
      `/v1/empresas/${empresaId}/establecimientos/${establecimientoId}/puntos-emision/${puntoEmisionId}/secuenciales`)
    return resp.data
  },

  /**
   * Configura el secuencial de (puntoEmision, tipo, ambiente). Si no existe lo crea; si
   * existe actualiza próximoNumero a ultimoNumeroEmitido + 1.
   */
  async configurarSecuencial(empresaId: string, establecimientoId: string, puntoEmisionId: string,
                              tipoComprobante: TipoComprobante, ambiente: 'PRUEBAS' | 'PRODUCCION',
                              ultimoNumeroEmitido: number): Promise<SecuencialResponse> {
    const resp = await api.put<SecuencialResponse>(
      `/v1/empresas/${empresaId}/establecimientos/${establecimientoId}/puntos-emision/${puntoEmisionId}/secuenciales`,
      { tipoComprobante, ambiente, ultimoNumeroEmitido })
    return resp.data
  },

  /** Historial de certs (activos + desactivados) del más reciente al más viejo. */
  async historialCertificados(empresaId: string): Promise<CertificadoView[]> {
    const resp = await api.get<CertificadoView[]>(`/v1/empresas/${empresaId}/certificado/historial`)
    return resp.data
  },

  /**
   * Promueve un cert inactivo a activo. Si hay otro activo, lo desactiva.
   * Multi-cert UX: tener Lazzate cargado + Security Data cargado, y elegir cuál usar.
   */
  async activarCertificado(empresaId: string, certificadoId: string): Promise<CertificadoView> {
    const resp = await api.post<CertificadoView>(
      `/v1/empresas/${empresaId}/certificado/${certificadoId}/activar`)
    return resp.data
  },

  /**
   * Elimina definitivamente un cert. Backend rechaza si tiene comprobantes
   * (409 CERTIFICADO_TIENE_COMPROBANTES) para preservar trazabilidad legal.
   */
  async eliminarCertificado(empresaId: string, certificadoId: string): Promise<void> {
    await api.delete(`/v1/empresas/${empresaId}/certificado/${certificadoId}`)
  },
}
