import type { EstadoComprobante } from '@/api/emision'

type Severity = 'info' | 'success' | 'warn' | 'danger' | 'secondary'

export const SEVERITY_POR_ESTADO: Record<EstadoComprobante, Severity> = {
  BORRADOR: 'secondary',
  FIRMADA: 'info',
  ENVIADA: 'info',
  EN_PROCESO: 'info',
  AUTORIZADA: 'success',
  DEVUELTA: 'warn',
  NO_AUTORIZADA: 'danger',
  ABANDONADA: 'secondary',
}

export const LABEL_POR_ESTADO: Record<EstadoComprobante, string> = {
  BORRADOR: 'Borrador',
  FIRMADA: 'Firmada',
  ENVIADA: 'Enviada',
  EN_PROCESO: 'En proceso',
  AUTORIZADA: 'Autorizada',
  DEVUELTA: 'Devuelta',
  NO_AUTORIZADA: 'No autorizada',
  ABANDONADA: 'Abandonada',
}

export const OPCIONES_FILTRO_ESTADO: { label: string; value: EstadoComprobante | null }[] = [
  { label: 'Todos', value: null },
  ...(Object.entries(LABEL_POR_ESTADO) as [EstadoComprobante, string][]).map(([value, label]) => ({
    label,
    value,
  })),
]
