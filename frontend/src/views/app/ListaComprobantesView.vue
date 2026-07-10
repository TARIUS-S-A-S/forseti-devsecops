<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  emisionApi,
  type ComprobanteResponse,
  type EstadoComprobante,
} from '@/api/emision'
import { SEVERITY_POR_ESTADO, OPCIONES_FILTRO_ESTADO } from '@/constants/comprobante'
import { mensajeDeError } from '@/composables/useApiError'
import Select from 'primevue/select'
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Message from 'primevue/message'
import ColaSriPanel from '@/components/ColaSriPanel.vue'

const router = useRouter()
const auth = useAuthStore()
const empresaId = computed(() => auth.empresaActivaId)

const comprobantes = ref<ComprobanteResponse[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const filtroEstado = ref<EstadoComprobante | null>(null)

async function cargar() {
  if (!empresaId.value) {
    error.value = 'No hay empresa activa'
    loading.value = false
    return
  }
  loading.value = true
  error.value = null
  try {
    comprobantes.value = await emisionApi.listar(filtroEstado.value ?? undefined)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudieron cargar los comprobantes')
  } finally {
    loading.value = false
  }
}

watch(filtroEstado, () => { cargar() })
onMounted(() => { cargar() })

function abrirDetalle(c: ComprobanteResponse) {
  router.push({ name: 'comprobante-detalle', params: { id: c.id } })
}

function formatFecha(iso: string): string {
  return new Date(iso).toLocaleDateString('es-EC')
}
</script>

<template>
  <div class="page-shell">
    <header class="page-head">
      <h1>Comprobantes</h1>
      <p class="page-sub">Facturas y notas emitidas por tu empresa.</p>
    </header>

    <ColaSriPanel />

    <div class="toolbar">
      <Select v-model="filtroEstado" :options="OPCIONES_FILTRO_ESTADO"
        option-label="label" option-value="value"
        placeholder="Filtrar por estado" class="filter" />
      <Button label="Nueva factura" icon="pi pi-plus"
        @click="router.push({ name: 'comprobante-nuevo' })" />
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div class="table-scroll">
      <DataTable :value="comprobantes" :loading="loading"
        empty-message="Todavía no emitiste comprobantes. Click en 'Nueva factura' para empezar."
        striped-rows paginator :rows="20" :rows-per-page-options="[10, 20, 50, 100]"
        class="table" @row-click="(e) => abrirDetalle(e.data)">
        <Column field="numeroComprobante" header="Número" />
        <Column field="fechaEmision" header="Fecha">
          <template #body="{ data }">{{ formatFecha(data.fechaEmision) }}</template>
        </Column>
        <Column header="Receptor">
          <template #body="{ data }">{{ data.receptor.razonSocial }}</template>
        </Column>
        <Column header="Total" class="num">
          <template #body="{ data }">${{ data.totales.importeTotal }}</template>
        </Column>
        <Column field="ambiente" header="Ambiente">
          <template #body="{ data }">
            <Tag :value="data.ambiente" :severity="data.ambiente === 'PRODUCCION' ? 'warn' : 'info'" />
          </template>
        </Column>
        <Column field="estado" header="Estado">
          <template #body="{ data }">
            <Tag :value="data.estado" :severity="SEVERITY_POR_ESTADO[data.estado as EstadoComprobante]" />
          </template>
        </Column>
        <Column header="" class="actions">
          <template #body="{ data }">
            <Button icon="pi pi-eye" text rounded size="small"
              @click.stop="abrirDetalle(data)" v-tooltip.left="'Ver detalle'" />
          </template>
        </Column>
      </DataTable>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.filter { min-width: 220px; }
.table :deep(tr) { cursor: pointer; }
.table :deep(.num) { text-align: right; }
.table :deep(.actions) { width: 60px; text-align: right; }
</style>
