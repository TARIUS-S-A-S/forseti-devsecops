<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'
import SriStatusBanner from '@/components/SriStatusBanner.vue'
import Menu from 'primevue/menu'
import Button from 'primevue/button'

const router = useRouter()
const auth = useAuthStore()

const sidebarOpen = ref(true)
const menu = ref()

const empresaActiva = computed(() => auth.empresaActiva)
const empresaMenu = ref()

const userMenuItems = computed(() => [
  { label: auth.user?.email ?? '', disabled: true },
  { separator: true },
  { label: 'Seguridad (2FA)', icon: 'pi pi-shield', command: () => router.push('/app/seguridad') },
  { separator: true },
  { label: 'Cerrar sesión', icon: 'pi pi-sign-out', command: async () => {
      await auth.logout()
      router.push({ name: 'login' })
  } },
])

const empresaMenuItems = computed(() => {
  const items: Array<Record<string, unknown>> = []
  for (const e of auth.user?.empresas ?? []) {
    items.push({
      label: e.razonSocial,
      icon: e.id === auth.empresaActivaId ? 'pi pi-check' : 'pi pi-building',
      command: async () => {
        await auth.cambiarEmpresaActiva(e.id)
      },
    })
  }
  items.push({ separator: true })
  items.push({
    label: 'Crear empresa nueva',
    icon: 'pi pi-plus',
    command: () => router.push({ name: 'empresa-nueva' }),
  })
  return items
})

interface NavItem {
  label: string
  icon: string
  to: string
  disabled?: boolean
  badge?: string
}

interface NavGroup {
  label: string
  items: NavItem[]
}

const navGroups = computed<NavGroup[]>(() => {
  const operar: NavItem[] = [
    { label: 'Dashboard', icon: 'pi pi-home', to: '/app' },
    { label: 'Comprobantes', icon: 'pi pi-file', to: '/app/comprobantes' },
    { label: 'Compras', icon: 'pi pi-shopping-cart', to: '/app/compras' },
    { label: 'Ingresos manuales', icon: 'pi pi-plus-circle', to: '/app/ingresos-manuales' },
  ]
  const contab: NavItem[] = [
    { label: 'Caja y reportes', icon: 'pi pi-chart-line', to: '/app/caja' },
    { label: 'Obligaciones SRI', icon: 'pi pi-calendar-clock', to: '/app/obligaciones' },
    { label: 'Contabilidad', icon: 'pi pi-book', to: '/app/contabilidad', disabled: true, badge: 'Sprint 6' },
  ]
  const config: NavItem[] = [
    { label: 'Configuración empresa', icon: 'pi pi-cog', to: '/app/empresa/configuracion' },
  ]
  const rol = auth.empresaActiva?.rol
  if (rol === 'DUENO' || rol === 'ADMIN') {
    config.push({ label: 'Miembros', icon: 'pi pi-users', to: '/app/empresa/miembros' })
  }
  config.push({ label: 'Seguridad (2FA)', icon: 'pi pi-shield', to: '/app/seguridad' })

  return [
    { label: 'Operar', items: operar },
    { label: 'Contabilidad', items: contab },
    { label: 'Configuración', items: config },
  ]
})

function toggleUserMenu(event: Event) {
  menu.value.toggle(event)
}

function toggleEmpresaMenu(event: Event) {
  empresaMenu.value.toggle(event)
}
</script>

<template>
  <div class="app-layout">
    <!-- Sidebar -->
    <aside class="sidebar" :class="{ collapsed: !sidebarOpen }">
      <div class="sidebar-head">
        <RouterLink to="/app" class="brand">
          <ForsetiLogo variant="ink" :size="32" />
          <span v-if="sidebarOpen" class="forseti-wordmark brand-text">Forseti</span>
        </RouterLink>
      </div>

      <nav class="nav">
        <div v-for="group in navGroups" :key="group.label" class="nav-group">
          <div v-if="sidebarOpen" class="nav-group-label">{{ group.label }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.label"
            :to="item.to"
            class="nav-item"
            :class="{ disabled: item.disabled }"
            :tabindex="item.disabled ? -1 : 0"
            :title="!sidebarOpen ? item.label : undefined"
          >
            <i :class="['pi', item.icon]" />
            <span v-if="sidebarOpen" class="nav-label">{{ item.label }}</span>
            <span v-if="sidebarOpen && item.badge" class="nav-badge">{{ item.badge }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-footer">
        <EndorsementTarius v-if="sidebarOpen" />
      </div>
    </aside>

    <!-- Topbar + content -->
    <div class="main">
      <header class="topbar">
        <Button :icon="sidebarOpen ? 'pi pi-bars' : 'pi pi-bars'" text rounded
          @click="sidebarOpen = !sidebarOpen" />

        <div class="topbar-right">
          <button v-if="empresaActiva" class="empresa-active" type="button"
                  @click="toggleEmpresaMenu" aria-haspopup="true" aria-label="Cambiar empresa activa">
            <i class="pi pi-building" />
            <span class="empresa-name">{{ empresaActiva.razonSocial }}</span>
            <span class="empresa-rol">{{ empresaActiva.rol }}</span>
            <i class="pi pi-chevron-down" />
          </button>
          <Button v-else label="Crear empresa" icon="pi pi-plus" severity="secondary"
                   @click="router.push({ name: 'empresa-nueva' })" />
          <Menu ref="empresaMenu" :model="empresaMenuItems" :popup="true" />

          <Button :label="auth.user?.nombre ?? '...'" icon="pi pi-user" text
            @click="toggleUserMenu" />
          <Menu ref="menu" :model="userMenuItems" :popup="true" />
        </div>
      </header>

      <SriStatusBanner />

      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-layout { display: grid; grid-template-columns: auto 1fr; min-height: 100vh; background: var(--color-bg); }

.sidebar { display: flex; flex-direction: column; width: 240px; background: var(--color-surface); border-right: 1px solid var(--color-border); transition: width 0.2s var(--ease-out); }
.sidebar.collapsed { width: 72px; }
.sidebar-head { padding: var(--sp-6) var(--sp-4); border-bottom: 1px solid var(--color-border); }
.brand { display: flex; align-items: center; gap: var(--sp-2); color: var(--color-ink); }
.brand-text { font-size: var(--fs-lg); }

.nav { flex: 1; padding: var(--sp-4); display: flex; flex-direction: column; gap: var(--sp-4); overflow-y: auto; }
.nav-group { display: flex; flex-direction: column; gap: var(--sp-1); }
.nav-group-label { font-size: var(--fs-xs); color: var(--color-muted); text-transform: uppercase; letter-spacing: 0.06em; font-weight: 600; padding: 0 var(--sp-3) var(--sp-1); }
.nav-item { display: flex; align-items: center; gap: var(--sp-3); padding: var(--sp-3); border-radius: var(--radius-md); color: var(--color-ink); text-decoration: none; transition: background 0.15s; font-weight: 500; }
.nav-item:hover { background: var(--color-marca-bg); color: var(--color-marca-deep); }
.nav-item.router-link-active:not(.disabled) { background: var(--color-action); color: white; }
.nav-item.disabled { color: var(--color-muted); cursor: not-allowed; opacity: 0.5; pointer-events: none; }
.nav-label { flex: 1; }
.nav-badge { font-size: var(--fs-xs); background: var(--color-border); color: var(--color-muted); padding: 2px 6px; border-radius: var(--radius-sm); }

.sidebar-footer { padding: var(--sp-4); border-top: 1px solid var(--color-border); text-align: center; }

.main { display: flex; flex-direction: column; min-width: 0; }
.topbar { display: flex; align-items: center; justify-content: space-between; padding: var(--sp-3) var(--sp-6); background: var(--color-surface); border-bottom: 1px solid var(--color-border); }
.topbar-right { display: flex; align-items: center; gap: var(--sp-6); }
.empresa-active { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2) var(--sp-3); background: var(--color-marca-bg); border-radius: var(--radius-md); border: none; cursor: pointer; font: inherit; }
.empresa-active:hover { background: var(--color-marca-bg-hover, var(--color-marca-bg)); }
.empresa-active i { color: var(--color-marca); }
.empresa-name { font-weight: 600; color: var(--color-ink); }
.empresa-rol { font-size: var(--fs-xs); color: var(--color-muted); text-transform: uppercase; letter-spacing: 0.05em; }
.empresa-vacio { color: var(--color-muted); font-size: var(--fs-sm); }

.content { flex: 1; padding: var(--sp-8); overflow: auto; }

@media (max-width: 900px) {
  .sidebar { position: fixed; top: 0; bottom: 0; z-index: 50; }
  .sidebar.collapsed { transform: translateX(-100%); width: 240px; }
  .app-layout { grid-template-columns: 1fr; }
}
</style>
