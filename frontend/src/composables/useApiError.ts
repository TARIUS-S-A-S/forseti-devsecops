interface ApiErrorShape {
  response?: {
    status?: number
    data?: {
      message?: string
      code?: string
      traceId?: string
    }
  }
  message?: string
}

export interface ApiError {
  message: string
  code: string | null
  status: number | null
  traceId: string | null
}

export function parseApiError(e: unknown, fallback = 'Ocurrió un error inesperado'): ApiError {
  const err = e as ApiErrorShape
  return {
    message: err?.response?.data?.message ?? err?.message ?? fallback,
    code: err?.response?.data?.code ?? null,
    status: err?.response?.status ?? null,
    traceId: err?.response?.data?.traceId ?? null,
  }
}

export function mensajeDeError(e: unknown, fallback = 'Ocurrió un error inesperado'): string {
  return parseApiError(e, fallback).message
}
