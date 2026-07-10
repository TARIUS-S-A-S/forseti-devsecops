<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import Message from 'primevue/message'

// Cada tab muestra el componente/vista existente — sin duplicar lógica.
import PerfilEmpresaView from '@/views/app/PerfilEmpresaView.vue'
import EstablecimientosView from '@/views/app/EstablecimientosView.vue'
import ObligacionesView from '@/views/app/ObligacionesView.vue'
import MiembrosView from '@/views/app/MiembrosView.vue'
import FirmaElectronicaTab from '@/components/empresa/FirmaElectronicaTab.vue'
import AmbienteToggle from '@/components/empresa/AmbienteToggle.vue'
import ZonaPeligroEmpresa from '@/components/empresa/ZonaPeligroEmpresa.vue'
import type { Empresa } from '@/api/empresa'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const empresaId = computed(() => auth.empresaActivaId)

// La empresa activa viene del auth store; al cambiar ambiente recargamos.
const empresaActiva = computed<Empresa | null>(() => {
  const e = auth.empresaActiva
  if (!e) return null
  return e as unknown as Empresa
})
function onAmbienteCambiado() {
  // recargar sesión para que el badge del topbar refleje el cambio
  void auth.fetchUser()
}

const TABS = [
  { value: 'datos',           label: 'Datos y tributario', icon: 'pi pi-id-card',         rol: null },
  { value: 'establecimientos',label: 'Establecimientos',   icon: 'pi pi-map-marker',      rol: null },
  { value: 'firma',           label: 'Firma electrónica',  icon: 'pi pi-key',             rol: null },
  { value: 'obligaciones',    label: 'Obligaciones SRI',   icon: 'pi pi-calendar-clock',  rol: null },
  { value: 'miembros',        label: 'Miembros',           icon: 'pi pi-users',           rol: 'DUENO_O_ADMIN' },
] as const

const tabsVisibles = computed(() => {
  const rol = auth.empresaActiva?.rol
  return TABS.filter(t => {
    if (t.rol === 'DUENO_O_ADMIN') return rol === 'DUENO' || rol === 'ADMIN'
    return true
  })
})

const tabActiva = computed({
  get() {
    const q = String(route.query.tab ?? 'datos')
    return tabsVisibles.value.some(t => t.value === q) ? q : 'datos'
  },
  set(v: string) {
    router.replace({ query: { ...route.query, tab: v } })
  },
})

watch(empresaId, (nuevo) => {
  if (!nuevo) router.replace({ name: 'empresa-nueva' })
})
</script>

<template>
  <div class="page-shell">
    <header class="page-head">
      <h1>Configuración de empresa</h1>
      <p class="page-sub" v-if="auth.empresaActiva">
        {{ auth.empresaActiva.razonSocial }} — gestioná todo lo de tu empresa desde acá.
      </p>
    </header>

    <Message v-if="!empresaId" severity="warn" :closable="false">
      No hay empresa activa. Seleccioná una en el topbar.
    </Message>

    <Tabs v-else v-model:value="tabActiva" class="tabs">
      <TabList>
        <Tab v-for="t in tabsVisibles" :key="t.value" :value="t.value">
          <i :class="['pi', t.icon]" />
          <span class="tab-label">{{ t.label }}</span>
        </Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="datos">
          <AmbienteToggle :empresa="empresaActiva" @cambiado="onAmbienteCambiado" />
          <div class="separador" />
          <PerfilEmpresaView />
          <div class="separador" />
          <ZonaPeligroEmpresa :empresa="empresaActiva" />
        </TabPanel>
        <TabPanel value="establecimientos">
          <EstablecimientosView />
        </TabPanel>
        <TabPanel value="firma">
          <FirmaElectronicaTab :empresa-id="empresaId" />
        </TabPanel>
        <TabPanel value="obligaciones">
          <ObligacionesView />
        </TabPanel>
        <TabPanel value="miembros" v-if="tabsVisibles.some(t => t.value === 'miembros')">
          <MiembrosView />
        </TabPanel>
      </TabPanels>
    </Tabs>
  </div>
</template>

<style scoped>
.tabs :deep(.p-tablist) { overflow-x: auto; }
.tabs :deep(.p-tablist-tab-list) { gap: var(--sp-2); flex-wrap: nowrap; }
.tabs :deep(.p-tab) { display: flex; align-items: center; gap: var(--sp-2); white-space: nowrap; }
.tabs :deep(.p-tabpanels) { padding-top: var(--sp-5); }
.tab-label { font-weight: 500; }
.separador { height: 2px; background: var(--color-border); margin: var(--sp-6) 0; border-radius: 1px; }

@media (max-width: 768px) {
  .tab-label { font-size: var(--fs-sm); }
}
</style>
