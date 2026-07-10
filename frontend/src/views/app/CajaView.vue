<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { reportesApi, descargarBlob, type FlujoCajaResponse } from '@/api/reportes'
import { mensajeDeError } from '@/composables/useApiError'
import Card from 'primevue/card'
import Button from 'primevue/button'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'

const hoy = new Date()
const inicioMes = new Date(hoy.getFullYear(), hoy.getMonth(), 1).toISOString().slice(0, 10)
const finHoy = hoy.toISOString().slice(0, 10)

const desde = ref<string>(inicioMes)
const hasta = ref<string>(finHoy)
const datos = ref<FlujoCajaResponse | null>(null)
const cargando = ref(true)
const error = ref<string | null>(null)
const descargando = ref<'compras' | 'ventas' | null>(null)

async function cargar() {
  cargando.value = true
  error.value = null
  try {
    datos.value = await reportesApi.flujoCaja(desde.value, hasta.value)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo cargar el flujo de caja')
  } finally {
    cargando.value = false
  }
}

onMounted(cargar)
watch([desde, hasta], () => cargar())

async function descargarCompras() {
  descargando.value = 'compras'
  try {
    const blob = await reportesApi.descargarCsvCompras(desde.value, hasta.value)
    descargarBlob(blob, `compras-${desde.value}_${hasta.value}.csv`)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo descargar el CSV de compras')
  } finally {
    descargando.value = null
  }
}

async function descargarVentas() {
  descargando.value = 'ventas'
  try {
    const blob = await reportesApi.descargarCsvVentas(desde.value, hasta.value)
    descargarBlob(blob, `ventas-${desde.value}_${hasta.value}.csv`)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo descargar el CSV de ventas')
  } finally {
    descargando.value = null
  }
}

const fmt = new Intl.NumberFormat('es-EC', { style: 'currency', currency: 'USD' })

const colorSaldo = computed(() => {
  if (!datos.value) return 'var(--color-muted)'
  return datos.value.saldoCobradoMenosPagado >= 0
    ? 'var(--color-success)'
    : 'var(--color-danger)'
})
</script>

<template>
  <div class="page-shell">
    <header class="page-head">
      <h1>Caja y reportes</h1>
      <p class="page-sub">
        Flujo del mes: ingresos cobrados vs egresos pagados. Exports CSV para la contadora
        (compras y ventas con bases por tarifa, listos para Excel).
      </p>
    </header>

    <Message v-if="error" severity="error" :closable="true" @close="error = null">{{ error }}</Message>

    <Card class="card">
      <template #title>Período</template>
      <template #content>
        <div class="periodo">
          <div class="field">
            <label>Desde</label>
            <DatePicker v-model:value="desde" date-format="yy-mm-dd" fluid />
          </div>
          <div class="field">
            <label>Hasta</label>
            <DatePicker v-model:value="hasta" date-format="yy-mm-dd" fluid />
          </div>
        </div>
      </template>
    </Card>

    <div v-if="datos" class="kpis">
      <Card class="kpi">
        <template #title>Ingresos cobrados</template>
        <template #content>
          <div class="valor success">{{ fmt.format(datos.totalIngresosCobrados) }}</div>
          <div class="sub">+ {{ fmt.format(datos.totalIngresosPendientes) }} pendientes</div>
        </template>
      </Card>

      <Card class="kpi">
        <template #title>Egresos pagados</template>
        <template #content>
          <div class="valor danger">−{{ fmt.format(datos.totalEgresosPagados) }}</div>
          <div class="sub">+ {{ fmt.format(datos.totalEgresosPendientes) }} pendientes</div>
        </template>
      </Card>

      <Card class="kpi saldo">
        <template #title>Saldo del período</template>
        <template #content>
          <div class="valor" :style="{ color: colorSaldo }">
            {{ fmt.format(datos.saldoCobradoMenosPagado) }}
          </div>
          <div class="sub">cobrado − pagado</div>
        </template>
      </Card>
    </div>

    <div v-else-if="cargando" class="loading">Cargando…</div>

    <Card class="card">
      <template #title>Exports para la contadora</template>
      <template #content>
        <p class="ayuda">
          CSV con BOM UTF-8 y separador ";". Abrí directo en Excel — encoding y columnas
          van OK. Incluye solo movimientos NO anulados.
        </p>
        <div class="exports">
          <Button label="Descargar compras CSV" icon="pi pi-download" outlined
                  :loading="descargando === 'compras'" @click="descargarCompras" />
          <Button label="Descargar ventas CSV" icon="pi pi-download" outlined
                  :loading="descargando === 'ventas'" @click="descargarVentas" />
        </div>
      </template>
    </Card>
  </div>
</template>

<style scoped>
.periodo { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); max-width: 540px; }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; font-size: var(--fs-sm); }
.kpis { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-4); }
.kpi .valor { font-size: var(--fs-2xl); font-family: var(--font-mono); font-weight: 700; }
.kpi .valor.success { color: var(--color-success); }
.kpi .valor.danger { color: var(--color-danger); }
.kpi .sub { color: var(--color-muted); font-size: var(--fs-sm); margin-top: var(--sp-1); }
.kpi.saldo { background: var(--color-marca-bg); border: 1px solid var(--color-marca-soft); }
.loading { padding: var(--sp-8); text-align: center; color: var(--color-muted); }
.ayuda { color: var(--color-muted); margin: 0 0 var(--sp-3); font-size: var(--fs-sm); }
.exports { display: flex; gap: var(--sp-3); flex-wrap: wrap; }

@media (max-width: 768px) {
  .kpis { grid-template-columns: 1fr; }
  .periodo { grid-template-columns: 1fr; }
}
</style>
