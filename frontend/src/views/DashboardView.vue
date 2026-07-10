<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { empresaApi, type CertificadoView } from '@/api/empresa'
import { obligacionApi, type ObligacionEmpresa } from '@/api/obligacion'
import Card from 'primevue/card'
import Message from 'primevue/message'
import Button from 'primevue/button'

const auth = useAuthStore()
const router = useRouter()

const certificado = ref<CertificadoView | null>(null)
const activadas = ref<ObligacionEmpresa[]>([])

onMounted(async () => {
  if (auth.empresaActivaId) {
    try {
      certificado.value = await empresaApi.certificadoActivo(auth.empresaActivaId)
      activadas.value = await obligacionApi.activadas(auth.empresaActivaId)
    } catch {
      // silencioso — el dashboard sigue mostrando lo que pueda
    }
  }
})

const obligacionesActivas = computed(() => activadas.value.filter(o => o.activa).length)

const cards = computed(() => [
  { titulo: 'Facturas emitidas', valor: '—', icono: 'pi pi-file', color: 'var(--color-action)', sprint: 'Sprint 3' },
  {
    titulo: 'Certificado .p12',
    valor: certificado.value ? `${certificado.value.diasParaCaducar}d` : '—',
    icono: 'pi pi-key',
    color: certificado.value
      ? (certificado.value.diasParaCaducar < 30 ? 'var(--color-danger)' : 'var(--color-success)')
      : 'var(--color-muted)',
    sprint: certificado.value ? 'para caducar' : 'pendiente de carga',
  },
  { titulo: 'Obligaciones activas', valor: String(obligacionesActivas.value), icono: 'pi pi-calendar-clock', color: 'var(--color-marca)', sprint: '' },
  { titulo: 'Empresas activas', valor: String(auth.user?.empresas.length ?? 0), icono: 'pi pi-building', color: 'var(--color-success)', sprint: '' },
])
</script>

<template>
  <div class="dashboard">
    <div class="header">
      <h1 class="title">Hola, {{ auth.user?.nombre }}</h1>
      <p class="sub">
        Estás en el dashboard de Forseti.
        <template v-if="!auth.empresaActiva">
          Para empezar, dale de alta a una empresa.
        </template>
      </p>
    </div>

    <div class="cards">
      <Card v-for="c in cards" :key="c.titulo" class="card">
        <template #content>
          <div class="card-inner">
            <div class="card-icon" :style="{ background: c.color }">
              <i :class="['pi', c.icono]" />
            </div>
            <div class="card-data">
              <div class="card-titulo">{{ c.titulo }}</div>
              <div class="card-valor tabular">{{ c.valor }}</div>
              <div v-if="c.sprint" class="card-sprint">{{ c.sprint }}</div>
            </div>
          </div>
        </template>
      </Card>
    </div>

    <Message v-if="auth.empresaActivaId && !certificado" severity="warn" :closable="false" class="banner">
      <i class="pi pi-exclamation-triangle" />
      <strong>Te falta cargar tu certificado .p12.</strong>
      Sin él no podemos firmar facturas para el SRI.
      <Button label="Cargar ahora" icon="pi pi-upload" text
              @click="router.push({ name: 'cert-upload', params: { empresaId: auth.empresaActivaId! } })" />
    </Message>

    <Message severity="info" :closable="false" class="banner">
      <i class="pi pi-info-circle" />
      <strong>Forseti está en piloto.</strong> Próximos sprints habilitan: emisión electrónica al SRI (Sprint 3),
      RIDE PDF + 1.ª factura real (Sprint 4).
    </Message>
  </div>
</template>

<style scoped>
.dashboard { max-width: 1200px; margin: 0 auto; }
.header { margin-bottom: var(--sp-8); }
.title { font-size: var(--fs-2xl); letter-spacing: -0.02em; margin-bottom: var(--sp-2); }
.sub { color: var(--color-muted); }

.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--sp-4); margin-bottom: var(--sp-8); }
.card { border: 1px solid var(--color-border); border-radius: var(--radius-lg); }
.card-inner { display: flex; align-items: center; gap: var(--sp-4); padding: var(--sp-2); }
.card-icon { width: 48px; height: 48px; border-radius: var(--radius-md); display: flex; align-items: center; justify-content: center; color: white; font-size: 22px; flex-shrink: 0; }
.card-data { flex: 1; min-width: 0; }
.card-titulo { font-size: var(--fs-sm); color: var(--color-muted); margin-bottom: 2px; }
.card-valor { font-size: var(--fs-xl); font-weight: 600; color: var(--color-ink); }
.card-sprint { font-size: var(--fs-xs); color: var(--color-muted); margin-top: 2px; }

.banner { margin-top: var(--sp-8); }
.banner i { margin-right: var(--sp-2); }
</style>
