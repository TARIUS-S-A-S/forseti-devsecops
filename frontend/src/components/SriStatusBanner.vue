<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { sriApi, type EstadoSriResponse } from '@/api/sri'

const estado = ref<EstadoSriResponse | null>(null)
let intervalo: ReturnType<typeof setInterval> | null = null

// Refresca cada 30s. Si la pestaña está oculta el browser pausa el timer
// automáticamente (no consume si nadie mira).
const REFRESH_MS = 30_000

async function refrescar() {
  try {
    estado.value = await sriApi.estado()
  } catch {
    estado.value = null
  }
}

onMounted(async () => {
  await refrescar()
  intervalo = setInterval(refrescar, REFRESH_MS)
})

onUnmounted(() => {
  if (intervalo) clearInterval(intervalo)
})

// Mostramos el banner SOLO cuando produccion está CAIDO o DEGRADADO. Pruebas no impacta
// al usuario final (es ambiente sandbox para pruebas internas).
const visible = computed(() => {
  const prod = estado.value?.produccion
  if (!prod) return false
  return prod.estado === 'CAIDO' || prod.estado === 'DEGRADADO'
       || prod.circuitBreaker === 'OPEN'
})

const severidad = computed(() => {
  const prod = estado.value?.produccion
  if (!prod) return 'info'
  if (prod.estado === 'CAIDO' || prod.circuitBreaker === 'OPEN') return 'danger'
  return 'warning'
})

const mensaje = computed(() => {
  const prod = estado.value?.produccion
  if (!prod) return ''
  if (prod.circuitBreaker === 'OPEN') {
    return `El SRI está rechazando llamadas (${prod.fallosConsecutivos} fallos recientes). Tus facturas están firmadas y se enviarán automáticamente cuando se restablezca.`
  }
  if (prod.estado === 'CAIDO') {
    return 'El SRI no está respondiendo. Tus facturas quedan firmadas y se enviarán automáticamente cuando vuelva.'
  }
  // DEGRADADO
  return `El SRI está respondiendo lento (${prod.latenciaMs}ms). Las autorizaciones pueden demorar más de lo usual.`
})
</script>

<template>
  <div v-if="visible" class="banner" :class="severidad">
    <i class="pi" :class="severidad === 'danger' ? 'pi-exclamation-triangle' : 'pi-info-circle'" />
    <span>{{ mensaje }}</span>
  </div>
</template>

<style scoped>
.banner {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-6);
  font-size: var(--fs-sm);
  border-bottom: 1px solid;
}
.banner.danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
  border-color: var(--color-danger);
}
.banner.warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
  border-color: var(--color-warning);
}
.banner i {
  font-size: var(--fs-md);
}
</style>
