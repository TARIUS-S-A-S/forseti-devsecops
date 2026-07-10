import axios, { AxiosError, type AxiosInstance } from 'axios'
import router from '@/router'

const baseURL = import.meta.env.VITE_API_URL || '/api'

export const api: AxiosInstance = axios.create({
  baseURL,
  withCredentials: true,        // cookie de sesión httpOnly
  timeout: 30_000,
  xsrfCookieName: 'XSRF-TOKEN', // Spring Security CSRF cookie
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: { 'Content-Type': 'application/json' },
})

// Flag para evitar redirects múltiples (race condition con N requests simultáneos)
let redirecting = false

// Interceptor: redirigir a /login si 401 — UNA sola vez
api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (error.response?.status === 401 && !redirecting) {
      const currentPath = router.currentRoute.value.fullPath
      // No redirigir si ya estamos en login
      if (router.currentRoute.value.name !== 'login') {
        redirecting = true
        // Limpiar estado de auth si el store está cargado (lazy import para evitar circular dep)
        const { useAuthStore } = await import('@/stores/auth')
        useAuthStore().clearLocal()
        await router.push({
          name: 'login',
          query: { redirect: currentPath !== '/' ? currentPath : undefined },
        })
        redirecting = false
      }
    }
    return Promise.reject(error)
  },
)
