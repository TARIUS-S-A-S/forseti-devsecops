<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { sriApi, type ColaSriResponse } from '@/api/sri'

const cola = ref<ColaSriResponse | null>(null)
const cargando = ref(true)

onMounted(async () => {
  try {
    cola.value = await sriApi.cola()
  } finally {
    cargando.value = false
  }
})

const totalPendientes = computed(() => {
  const e = cola.value?.porEstado ?? {}
  return (e.FIRMADA ?? 0) + (e.ENVIADA ?? 0) + (e.EN_PROCESO ?? 0)
})

const totalAutorizadas = computed(() => cola.value?.porEstado?.AUTORIZADA ?? 0)
const totalRechazadas = computed(() => {
  const e = cola.value?.porEstado ?? {}
  return (e.NO_AUTORIZADA ?? 0) + (e.DEVUELTA ?? 0) + (e.ABANDONADA ?? 0)
})

function formatearAntiguedad(seg: number | null): string {
  if (seg == null) return '—'
  if (seg < 60) return `${Math.round(seg)}s`
  if (seg < 3600) return `${Math.round(seg / 60)}m`
  if (seg < 86400) return `${Math.round(seg / 3600)}h`
  return `${Math.round(seg / 86400)}d`
}

function formatearTasa(t: number | null): string {
  if (t == null) return '—'
  return `${(t * 100).toFixed(1)}%`
}

const alertaAntiguedad = computed(() => {
  const s = cola.value?.antiguedadMaxPendienteSeg
  if (s == null) return null
  if (s > 3600) return 'rojo'      // > 1h pendiente
  if (s > 900)  return 'amarillo'  // > 15min
  return null
})
</script>

<template>
  <div v-if="cargando" class="cola-loading">Cargando métricas…</div>
  <div v-else-if="cola" class="cola">
    <div class="card metric">
      <span class="label">Pendientes</span>
      <span class="value" :class="{ alerta: totalPendientes > 0 }">{{ totalPendientes }}</span>
      <span class="sub" :class="alertaAntiguedad ?? ''">
        Más viejo: {{ formatearAntiguedad(cola.antiguedadMaxPendienteSeg) }}
      </span>
    </div>
    <div class="card metric">
      <span class="label">Autorizadas (total)</span>
      <span class="value ok">{{ totalAutorizadas }}</span>
    </div>
    <div class="card metric">
      <span class="label">Rechazadas (total)</span>
      <span class="value" :class="{ alerta: totalRechazadas > 0 }">{{ totalRechazadas }}</span>
    </div>
    <div class="card metric">
      <span class="label">Tasa éxito 24h</span>
      <span class="value" :class="cola.tasaExito24h != null && cola.tasaExito24h < 0.95 ? 'alerta' : 'ok'">
        {{ formatearTasa(cola.tasaExito24h) }}
      </span>
      <span class="sub">{{ cola.autorizados24h }}/{{ cola.total24h }} autorizadas</span>
    </div>
    <div class="card metric">
      <span class="label">Tiempo prom autorización</span>
      <span class="value">
        {{ cola.tiempoPromAutorizacionSeg != null
          ? cola.tiempoPromAutorizacionSeg.toFixed(1) + 's'
          : '—' }}
      </span>
      <span class="sub">últimas 24h</span>
    </div>
  </div>
</template>

<style scoped>
.cola { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--sp-3); }
.cola-loading { color: var(--color-muted); padding: var(--sp-3); }
.card.metric { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--sp-4); display: flex; flex-direction: column; gap: var(--sp-1); }
.label { font-size: var(--fs-xs); color: var(--color-muted); text-transform: uppercase; letter-spacing: 0.05em; }
.value { font-size: var(--fs-2xl); font-family: var(--font-mono); font-weight: 600; }
.value.ok { color: var(--color-success); }
.value.alerta { color: var(--color-danger); }
.sub { font-size: var(--fs-xs); color: var(--color-muted); }
.sub.rojo { color: var(--color-danger); font-weight: 500; }
.sub.amarillo { color: var(--color-warning); font-weight: 500; }
</style>
