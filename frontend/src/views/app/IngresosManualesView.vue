<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  ingresosApi,
  type IngresoManualResponse,
  type CrearIngresoManualRequest,
} from '@/api/ingresos'
import { mensajeDeError } from '@/composables/useApiError'
import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import DatePicker from 'primevue/datepicker'
import Textarea from 'primevue/textarea'
import Message from 'primevue/message'
import ConfirmDialog from 'primevue/confirmdialog'
import { useConfirm } from 'primevue/useconfirm'

const auth = useAuthStore()
const empresaId = computed(() => auth.empresaActivaId)
const confirm = useConfirm()

const lista = ref<IngresoManualResponse[]>([])
const cargando = ref(true)
const error = ref<string | null>(null)
const ok = ref<string | null>(null)

const dialogNuevo = ref(false)
const submitting = ref(false)
const errorForm = ref<string | null>(null)
const fechaHoy = new Date().toISOString().slice(0, 10)
const form = ref<CrearIngresoManualRequest>({
  fechaEmision: fechaHoy,
  clienteRazonSocial: '',
  clienteIdentificacion: '',
  concepto: '',
  baseIva15: 0,
  baseIva0: 0,
  valorIva15: 0,
  retencionRecibida: 0,
  total: 0,
})

const dialogCobrar = ref(false)
const cobrarId = ref<string | null>(null)
const fechaCobro = ref(fechaHoy)
const errorCobrar = ref<string | null>(null)

async function cargar() {
  if (!empresaId.value) {
    error.value = 'No hay empresa activa'
    cargando.value = false
    return
  }
  cargando.value = true
  error.value = null
  try {
    lista.value = await ingresosApi.listar()
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudieron cargar los ingresos')
  } finally {
    cargando.value = false
  }
}

onMounted(cargar)

function abrirNuevo() {
  form.value = {
    fechaEmision: fechaHoy,
    clienteRazonSocial: '',
    clienteIdentificacion: '',
    concepto: '',
    baseIva15: 0,
    baseIva0: 0,
    valorIva15: 0,
    retencionRecibida: 0,
    total: 0,
  }
  errorForm.value = null
  dialogNuevo.value = true
}

const totalCalc = computed(() => {
  const f = form.value
  return (f.baseIva15 ?? 0) + (f.baseIva0 ?? 0) + (f.valorIva15 ?? 0)
       - (f.retencionRecibida ?? 0)
})

async function crearNuevo() {
  submitting.value = true
  errorForm.value = null
  try {
    if (!form.value.total) form.value.total = totalCalc.value
    await ingresosApi.crear(form.value)
    ok.value = 'Ingreso registrado.'
    dialogNuevo.value = false
    await cargar()
  } catch (e) {
    errorForm.value = mensajeDeError(e, 'No se pudo crear el ingreso')
  } finally {
    submitting.value = false
  }
}

function pedirAnular(i: IngresoManualResponse) {
  confirm.require({
    header: `Anular ingreso de ${i.clienteRazonSocial}`,
    message:
      'Anular NO borra el ingreso — queda visible para auditoría pero sale de totales y de la '
      + 'declaración. No se puede deshacer. ¿Confirmás?',
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, anular',
    acceptClass: 'p-button-danger',
    accept: async () => {
      try {
        await ingresosApi.anular(i.id, 'Anulado desde lista de ingresos')
        await cargar()
        ok.value = 'Ingreso anulado.'
      } catch (e) {
        error.value = mensajeDeError(e, 'No se pudo anular')
      }
    },
  })
}

function abrirCobrar(i: IngresoManualResponse) {
  cobrarId.value = i.id
  fechaCobro.value = fechaHoy
  errorCobrar.value = null
  dialogCobrar.value = true
}

async function confirmarCobro() {
  if (!cobrarId.value) return
  try {
    await ingresosApi.marcarCobrado(cobrarId.value, fechaCobro.value)
    dialogCobrar.value = false
    await cargar()
    ok.value = 'Ingreso marcado como cobrado.'
  } catch (e) {
    errorCobrar.value = mensajeDeError(e, 'No se pudo marcar cobrado')
  }
}

function severityCobro(estado: string) {
  if (estado === 'COBRADO') return 'success'
  if (estado === 'PARCIAL') return 'warn'
  return 'secondary'
}

function formatFecha(s: string) {
  return new Date(s).toLocaleDateString('es-EC')
}
</script>

<template>
  <div class="page-shell">
    <ConfirmDialog />

    <header class="page-head">
      <h1>Ingresos manuales</h1>
      <p class="page-sub">
        Ventas previas a Forseti o que no se emiten vía Forseti. El flujo normal de facturación
        es Comprobantes → Nueva factura.
      </p>
    </header>

    <Message v-if="ok" severity="success" :closable="true" @close="ok = null">{{ ok }}</Message>
    <Message v-if="error" severity="error" :closable="true" @close="error = null">{{ error }}</Message>

    <div class="toolbar">
      <Button label="Nuevo ingreso" icon="pi pi-plus" @click="abrirNuevo" />
    </div>

    <div class="table-scroll">
      <DataTable :value="lista" :loading="cargando" paginator :rows="20"
                 empty-message="Sin ingresos manuales en este período."
                 striped-rows>
        <Column field="fechaEmision" header="Fecha">
          <template #body="{ data }">{{ formatFecha((data as IngresoManualResponse).fechaEmision) }}</template>
        </Column>
        <Column field="clienteRazonSocial" header="Cliente" />
        <Column field="concepto" header="Concepto" />
        <Column field="total" header="Total" class="num">
          <template #body="{ data }">${{ (data as IngresoManualResponse).total }}</template>
        </Column>
        <Column field="estadoCobro" header="Cobro">
          <template #body="{ data }">
            <Tag :value="(data as IngresoManualResponse).estadoCobro"
                 :severity="severityCobro((data as IngresoManualResponse).estadoCobro)" />
          </template>
        </Column>
        <Column header="">
          <template #body="{ data }">
            <Tag v-if="(data as IngresoManualResponse).anulada" value="ANULADA" severity="danger" />
          </template>
        </Column>
        <Column header="Acciones" class="acciones-col">
          <template #body="{ data }">
            <div class="acciones" v-if="!(data as IngresoManualResponse).anulada">
              <Button v-if="(data as IngresoManualResponse).estadoCobro !== 'COBRADO'"
                      icon="pi pi-dollar" size="small" text rounded
                      v-tooltip.left="'Marcar cobrado'"
                      @click="abrirCobrar(data as IngresoManualResponse)" />
              <Button icon="pi pi-times" size="small" severity="danger" text rounded
                      v-tooltip.left="'Anular'"
                      @click="pedirAnular(data as IngresoManualResponse)" />
            </div>
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- Dialog Nuevo -->
    <Dialog v-model:visible="dialogNuevo" modal :draggable="false"
            header="Nuevo ingreso manual"
            :style="{ width: '560px' }" :breakpoints="{ '768px': '92vw' }">
      <form class="form-vert" @submit.prevent="crearNuevo">
        <div class="grid-2">
          <div class="field">
            <label>Fecha</label>
            <DatePicker v-model:value="form.fechaEmision" date-format="yy-mm-dd" fluid />
          </div>
          <div class="field">
            <label>ID cliente (opcional)</label>
            <InputText v-model="form.clienteIdentificacion" maxlength="20" fluid />
          </div>
        </div>
        <div class="field">
          <label>Cliente — razón social *</label>
          <InputText v-model="form.clienteRazonSocial" maxlength="300" fluid required />
        </div>
        <div class="field">
          <label>Concepto *</label>
          <Textarea v-model="form.concepto" rows="2" maxlength="500" auto-resize />
        </div>
        <div class="grid-2">
          <div class="field">
            <label>Base IVA 15%</label>
            <InputNumber v-model="form.baseIva15" :min="0" :max-fraction-digits="2" fluid />
          </div>
          <div class="field">
            <label>IVA 15%</label>
            <InputNumber v-model="form.valorIva15" :min="0" :max-fraction-digits="2" fluid />
          </div>
        </div>
        <div class="grid-2">
          <div class="field">
            <label>Base IVA 0%</label>
            <InputNumber v-model="form.baseIva0" :min="0" :max-fraction-digits="2" fluid />
          </div>
          <div class="field">
            <label>Retención recibida</label>
            <InputNumber v-model="form.retencionRecibida" :min="0" :max-fraction-digits="2" fluid />
          </div>
        </div>
        <div class="field">
          <label>Total recibido (calculado: ${{ totalCalc.toFixed(2) }})</label>
          <InputNumber v-model="form.total" :min="0" :max-fraction-digits="2" fluid />
        </div>
        <Message v-if="errorForm" severity="error" :closable="false">{{ errorForm }}</Message>
      </form>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogNuevo = false" />
        <Button :label="submitting ? 'Creando…' : 'Crear ingreso'" :loading="submitting"
                @click="crearNuevo" />
      </template>
    </Dialog>

    <!-- Dialog Cobrar -->
    <Dialog v-model:visible="dialogCobrar" modal :draggable="false"
            header="Marcar como cobrado"
            :style="{ width: '380px' }" :breakpoints="{ '768px': '92vw' }">
      <div class="form-vert">
        <div class="field">
          <label>Fecha del cobro</label>
          <DatePicker v-model:value="fechaCobro" date-format="yy-mm-dd" fluid />
        </div>
        <Message v-if="errorCobrar" severity="error" :closable="false">{{ errorCobrar }}</Message>
      </div>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogCobrar = false" />
        <Button label="Confirmar cobro" @click="confirmarCobro" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: var(--sp-3); }
.form-vert { display: flex; flex-direction: column; gap: var(--sp-3); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; font-size: var(--fs-sm); }
:deep(.num) { text-align: right; font-family: var(--font-mono); }
.acciones { display: flex; gap: var(--sp-1); }
.acciones-col { width: 110px; }
</style>
