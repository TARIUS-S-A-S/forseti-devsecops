import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // Landing público
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: 'Forseti — Facturación electrónica SRI Ecuador' },
    },

    // Auth (guest only)
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: 'Iniciar sesión — Forseti', requiresGuest: true },
    },
    {
      path: '/registro',
      name: 'registro',
      component: () => import('@/views/RegisterView.vue'),
      meta: { title: 'Crear cuenta — Forseti', requiresGuest: true },
    },
    {
      path: '/verificar-email',
      name: 'verificar-email',
      component: () => import('@/views/VerifyEmailView.vue'),
      meta: { title: 'Verificar email — Forseti' },
    },
    {
      path: '/recuperar',
      name: 'recuperar',
      component: () => import('@/views/ForgotPasswordView.vue'),
      meta: { title: 'Recuperar contraseña — Forseti', requiresGuest: true },
    },
    {
      path: '/reset-password',
      name: 'reset-password',
      component: () => import('@/views/ResetPasswordView.vue'),
      meta: { title: 'Resetear contraseña — Forseti' },
    },
    {
      path: '/invitacion/:token',
      name: 'invitacion',
      component: () => import('@/views/AceptarInvitacionView.vue'),
      meta: { title: 'Aceptar invitación — Forseti' },
    },

    // App autenticada
    {
      path: '/app',
      component: () => import('@/layouts/AppLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: 'Dashboard — Forseti' },
        },
        {
          path: 'seguridad',
          name: 'seguridad',
          component: () => import('@/views/app/SeguridadView.vue'),
          meta: { title: 'Seguridad — Forseti' },
        },
        {
          path: 'empresas/nueva',
          name: 'empresa-nueva',
          component: () => import('@/views/app/EmpresaNuevaView.vue'),
          meta: { title: 'Nueva empresa — Forseti' },
        },
        {
          path: 'empresas/seleccionar',
          name: 'empresa-selector',
          component: () => import('@/views/app/EmpresaSelectorView.vue'),
          meta: { title: 'Elegí empresa — Forseti' },
        },
        // Vista unificada nueva — tabs con todo lo de Configuración de empresa.
        {
          path: 'empresa/configuracion',
          name: 'empresa-configuracion',
          component: () => import('@/views/app/EmpresaConfiguracionView.vue'),
          meta: { title: 'Configuración de empresa — Forseti' },
        },
        // ─── Redirects de las rutas viejas a la tab correspondiente ───
        {
          path: 'empresas/:empresaId/certificado',
          redirect: { name: 'empresa-configuracion', query: { tab: 'firma' } },
        },
        {
          path: 'empresa/perfil',
          redirect: { name: 'empresa-configuracion', query: { tab: 'datos' } },
        },
        {
          path: 'empresa/establecimientos',
          redirect: { name: 'empresa-configuracion', query: { tab: 'establecimientos' } },
        },
        {
          path: 'obligaciones',
          redirect: { name: 'empresa-configuracion', query: { tab: 'obligaciones' } },
        },
        {
          path: 'empresa/miembros',
          redirect: { name: 'empresa-configuracion', query: { tab: 'miembros' } },
        },
        {
          path: 'cambiar-password',
          name: 'cambiar-password',
          component: () => import('@/views/app/CambiarPasswordObligatorioView.vue'),
          meta: { title: 'Cambiar contraseña — Forseti' },
        },
        {
          path: 'comprobantes',
          name: 'comprobantes',
          component: () => import('@/views/app/ListaComprobantesView.vue'),
          meta: { title: 'Comprobantes — Forseti' },
        },
        {
          path: 'comprobantes/nueva',
          name: 'comprobante-nuevo',
          component: () => import('@/views/app/CrearFacturaView.vue'),
          meta: { title: 'Nueva factura — Forseti' },
        },
        {
          path: 'comprobantes/:id',
          name: 'comprobante-detalle',
          component: () => import('@/views/app/DetalleComprobanteView.vue'),
          meta: { title: 'Detalle comprobante — Forseti' },
        },

        // Sprint 5 — Compras, ingresos manuales, caja
        {
          path: 'compras',
          name: 'compras',
          component: () => import('@/views/app/ComprasView.vue'),
          meta: { title: 'Compras — Forseti' },
        },
        {
          path: 'compras/:id',
          name: 'compra-detalle',
          component: () => import('@/views/app/CompraDetalleView.vue'),
          meta: { title: 'Detalle de compra — Forseti' },
        },
        {
          path: 'ingresos-manuales',
          name: 'ingresos-manuales',
          component: () => import('@/views/app/IngresosManualesView.vue'),
          meta: { title: 'Ingresos manuales — Forseti' },
        },
        {
          path: 'caja',
          name: 'caja',
          component: () => import('@/views/app/CajaView.vue'),
          meta: { title: 'Caja y reportes — Forseti' },
        },
      ],
    },

    // 404 catch-all
    {
      path: '/:pathMatch(.*)*',
      redirect: '/',
    },
  ],
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

let firstFetchDone = false

router.beforeEach(async (to) => {
  if (to.meta.title) {
    document.title = String(to.meta.title)
  }

  const auth = useAuthStore()
  // Fetch user UNA SOLA VEZ al inicio (después usa cache de la store)
  if (!firstFetchDone) {
    firstFetchDone = true
    await auth.fetchUser()
  }

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresGuest && auth.isAuthenticated) {
    return { name: 'dashboard' }
  }

  // Forzar cambio de password si debeCambiarPassword=true (usuarios creados con username + pwd temporal)
  if (
    to.meta.requiresAuth &&
    auth.isAuthenticated &&
    auth.user?.debeCambiarPassword &&
    to.name !== 'cambiar-password'
  ) {
    return { name: 'cambiar-password' }
  }

  // Onboarding: si está autenticado pero no tiene empresas, redirigir a crear una
  // (excepto si ya va a empresa-nueva o a empresa-selector)
  if (
    to.meta.requiresAuth &&
    auth.isAuthenticated &&
    auth.user &&
    auth.user.empresas.length === 0 &&
    to.name !== 'empresa-nueva' &&
    to.name !== 'empresa-selector' &&
    to.name !== 'cambiar-password' &&
    to.name !== 'seguridad'
  ) {
    return { name: 'empresa-nueva' }
  }
})

export default router
