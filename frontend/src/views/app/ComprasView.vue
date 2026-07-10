<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  comprasApi,
  type CompraResponse,
  type CategoriaResponse,
  type CrearCompraRequest,
} from '@/api/compras'
import { mensajeDeError } from '@/composables/useApiError'
import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import DatePicker from 'primevue/datepicker'
import Select from 'primevue/select'
import FileUpload from 'primevue/fileupload'
import Checkbox from 'primevue/checkbox'
import Textarea from 'primevue/textarea'

const router = useRouter()
const auth = useAuthStore()
const empresaId = computed(() => auth.empresaActivaId)

const compras = ref<CompraResponse[]>([])
const categorias = ref<CategoriaResponse[]>([])
const cargando = ref(true)
const error = ref<string | null>(null)
const ok = ref<string | null>(null)

const dialogXml = ref(false)
const xmlArchivo = ref<File | null>(null)
const xmlCategoriaId = ref<string | null>(null)
const subiendoXml = ref(false)
const errorXml = ref<string | null>(null)

const dialogManual = ref(false)
const submitting = ref(false)
const errorManual = ref<string | null>(null)
const fechaHoy = new Date().toISOString().slice(0, 10)
const formManual = ref<CrearCompraRequest>({
  fechaEmision: fechaHoy,
  proveedorTipoId: '04',
  proveedorIdentificacion: '',
  proveedorRazonSocial: '',
  tipoDocumento: 'FACTURA',
  numeroDocumento: '',
  concepto: '',
  categoriaId: null,
  baseIva15: 0,
  baseIva0: 0,
  baseNoObjeto: 0,
  baseExento: 0,
  valorIva15: 0,
  retencionIr: 0,
  retencionIva: 0,
  total: 0,
  deducible: true,
})

const tipoOpts = [
  { label: 'Factura', value: 'FACTURA' },
  { label: 'Nota de Crédito', value: 'NOTA_CREDITO' },
  { label: 'Nota de Débito', value: 'NOTA_DEBITO' },
  { label: 'Liquidación de Compra', value: 'LIQUIDACION_COMPRA' },
  { label: 'Recibo', value: 'RECIBO' },
  { label: 'Otro', value: 'OTRO' },
]
const tipoIdOpts = [
  { label: 'RUC (04)', value: '04' },
  { label: 'Cédula (05)', value: '05' },
  { label: 'Pasaporte (06)', value: '06' },
  { label: 'Identif. exterior (08)', value: '08' },
]

async function cargar() {
  if (!empresaId.value) {
    error.value = 'No hay empresa activa'
    cargando.value = false
    return
  }
  cargando.value = true
  error.value = null
  try {
    const [list, cats] = await Promise.all([
      comprasApi.listar(),
      comprasApi.listarCategorias(),
    ])
    compras.value = list
    categorias.value = cats
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudieron cargar las compras')
  } finally {
    cargando.value = false
  }
}

onMounted(cargar)

function abrirDetalle(c: CompraResponse) {
  router.push({ name: 'compra-detalle', params: { id: c.id } })
}

function abrirDialogXml() {
  xmlArchivo.value = null
  xmlCategoriaId.value = null
  errorXml.value = null
  dialogXml.value = true
}

function fileSelected(event: { files: File[] }) {
  xmlArchivo.value = event.files[0] ?? null
}

async function subirXml() {
  if (!xmlArchivo.value) {
    errorXml.value = 'Seleccioná el archivo XML primero.'
    return
  }
  subiendoXml.value = true
  errorXml.value = null
  try {
    const c = await comprasApi.crearDesdeXml(xmlArchivo.value, xmlCategoriaId.value ?? undefined)
    ok.value = `Compra cargada desde XML: ${c.proveedorRazonSocial} — ${c.numeroDocumento}`
    dialogXml.value = false
    await cargar()
  } catch (e) {
    errorXml.value = mensajeDeError(e, 'No se pudo cargar el XML')
  } finally {
    subiendoXml.value = false
  }
}

function abrirDialogManual() {
  formManual.value = {
    fechaEmision: fechaHoy,
    proveedorTipoId: '04',
    proveedorIdentificacion: '',
    proveedorRazonSocial: '',
    tipoDocumento: 'FACTURA',
    numeroDocumento: '',
    concepto: '',
    categoriaId: null,
    baseIva15: 0,
    baseIva0: 0,
    baseNoObjeto: 0,
    baseExento: 0,
    valorIva15: 0,
    retencionIr: 0,
    retencionIva: 0,
    total: 0,
    deducible: true,
  }
  errorManual.value = null
  dialogManual.value = true
}

const totalCalculado = computed(() => {
  const f = formManual.value
  return (f.baseIva15 ?? 0) + (f.baseIva0 ?? 0)
       + (f.baseNoObjeto ?? 0) + (f.baseExento ?? 0)
       + (f.valorIva15 ?? 0) - (f.retencionIr ?? 0) - (f.retencionIva ?? 0)
})

async function crearManual() {
  submitting.value = true
  errorManual.value = null
  try {
    // Si el usuario no tocó el total, usamos el calculado
    if (!formManual.value.total || formManual.value.total === 0) {
      formManual.value.total = totalCalculado.value
    }
    const c = await comprasApi.crearManual(formManual.value)
    ok.value = `Compra creada: ${c.proveedorRazonSocial} — ${c.numeroDocumento}`
    dialogManual.value = false
    await cargar()
  } catch (e) {
    errorManual.value = mensajeDeError(e, 'No se pudo crear la compra')
  } finally {
    submitting.value = false
  }
}

function severityEstadoPago(estado: string) {
  if (estado === 'PAGADO') return 'success'
  if (estado === 'PARCIAL') return 'warn'
  return 'secondary'
}

function formatFecha(s: string) {
  return new Date(s).toLocaleDateString('es-EC')
}
</script>

<template>
  <div class="page-shell">
    <header class="page-head">
      <h1>Compras y gastos</h1>
      <p class="page-sub">
        Facturas recibidas y gastos de la empresa. Cargá un XML SRI y Forseti autollena los datos.
      </p>
    </header>

    <Message v-if="ok" severity="success" :closable="true" @close="ok = null">{{ ok }}</Message>
    <Message v-if="error" severity="error" :closable="true" @close="error = null">{{ error }}</Message>

    <div class="toolbar">
      <Button label="Cargar XML SRI" icon="pi pi-upload" @click="abrirDialogXml" />
      <Button label="Alta manual" icon="pi pi-plus" severity="secondary" outlined
              @click="abrirDialogManual" />
    </div>

    <div class="table-scroll">
      <DataTable :value="compras" :loading="cargando" paginator :rows="20"
                 :rows-per-page-options="[10, 20, 50, 100]"
                 empty-message="Sin compras en este período. Cargá un XML SRI o ingresá una manual."
                 striped-rows class="tabla"
                 @row-click="(e) => abrirDetalle(e.data as CompraResponse)">
        <Column field="fechaEmision" header="Fecha">
          <template #body="{ data }">{{ formatFecha((data as CompraResponse).fechaEmision) }}</template>
        </Column>
        <Column field="proveedorRazonSocial" header="Proveedor" />
        <Column field="numeroDocumento" header="Número" />
        <Column field="tipoDocumento" header="Tipo">
          <template #body="{ data }">{{ (data as CompraResponse).tipoDocumento.replace('_', ' ') }}</template>
        </Column>
        <Column field="total" header="Total" class="num">
          <template #body="{ data }">${{ (data as CompraResponse).total }}</template>
        </Column>
        <Column field="estadoPago" header="Pago">
          <template #body="{ data }">
            <Tag :value="(data as CompraResponse).estadoPago"
                 :severity="severityEstadoPago((data as CompraResponse).estadoPago)" />
          </template>
        </Column>
        <Column field="origen" header="Origen">
          <template #body="{ data }">
            <Tag :value="(data as CompraResponse).origen"
                 :severity="(data as CompraResponse).origen === 'XML' ? 'info' : 'secondary'" />
          </template>
        </Column>
        <Column header="">
          <template #body="{ data }">
            <Tag v-if="(data as CompraResponse).anulada" value="ANULADA" severity="danger" />
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- Dialog Alta XML -->
    <Dialog v-model:visible="dialogXml" modal :draggable="false"
            header="Cargar factura recibida (XML SRI)"
            :style="{ width: '500px' }" :breakpoints="{ '768px': '92vw' }">
      <div class="form-vert">
        <p class="ayuda">
          Subí el XML autorizado que recibiste del proveedor. Forseti extrae proveedor,
          número, fechas, bases por tarifa e IVA — y bloquea duplicados.
        </p>
        <FileUpload mode="basic" accept=".xml,application/xml,text/xml"
                    :max-file-size="2 * 1024 * 1024"
                    choose-label="Seleccionar XML" @select="fileSelected" />
        <div v-if="xmlArchivo" class="archivo-info">
          <i class="pi pi-file" /> {{ xmlArchivo.name }}
          <span class="size">({{ Math.round(xmlArchivo.size / 1024) }} KB)</span>
        </div>
        <div class="field">
          <label>Categoría (opcional)</label>
          <Select v-model="xmlCategoriaId" :options="categorias"
                  option-label="nombre" option-value="id"
                  placeholder="Sin categoría" show-clear class="full-w" />
        </div>
        <Message v-if="errorXml" severity="error" :closable="false">{{ errorXml }}</Message>
      </div>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogXml = false" />
        <Button :label="subiendoXml ? 'Cargando…' : 'Cargar'" :loading="subiendoXml"
                :disabled="!xmlArchivo" @click="subirXml" />
      </template>
    </Dialog>

    <!-- Dialog Alta Manual -->
    <Dialog v-model:visible="dialogManual" modal :draggable="false"
            header="Alta manual de compra"
            :style="{ width: '680px' }" :breakpoints="{ '768px': '92vw' }">
      <form class="form-vert" @submit.prevent="crearManual">
        <div class="grid-2">
          <div class="field">
            <label>Fecha emisión</label>
            <DatePicker v-model:value="formManual.fechaEmision" date-format="yy-mm-dd" fluid />
          </div>
          <div class="field">
            <label>Tipo documento</label>
            <Select v-model="formManual.tipoDocumento" :options="tipoOpts"
                    option-label="label" option-value="value" fluid />
          </div>
        </div>
        <div class="grid-2">
          <div class="field">
            <label>Tipo ID proveedor</label>
            <Select v-model="formManual.proveedorTipoId" :options="tipoIdOpts"
                    option-label="label" option-value="value" fluid />
          </div>
          <div class="field">
            <label>Identificación proveedor</label>
            <InputText v-model="formManual.proveedorIdentificacion" maxlength="20" fluid />
          </div>
        </div>
        <div class="field">
          <label>Razón social proveedor</label>
          <InputText v-model="formManual.proveedorRazonSocial" maxlength="300" fluid />
        </div>
        <div class="grid-2">
          <div class="field">
            <label>Número documento</label>
            <InputText v-model="formManual.numeroDocumento" maxlength="50" fluid
                       placeholder="Ej: 001-001-000000123" />
          </div>
          <div class="field">
            <label>Categoría</label>
            <Select v-model="formManual.categoriaId" :options="categorias"
                    option-label="nombre" option-value="id"
                    placeholder="Sin categoría" show-clear fluid />
          </div>
        </div>
        <div class="field">
          <label>Concepto</label>
          <Textarea v-model="formManual.concepto" rows="2" maxlength="500" auto-resize />
        </div>

        <div class="grid-2">
          <div class="field">
            <label>Base IVA 15%</label>
            <InputNumber v-model="formManual.baseIva15" :min="0" :max-fraction-digits="2" fluid />
          </div>
          <div class="field">
            <label>IVA 15%</label>
            <InputNumber v-model="formManual.valorIva15" :min="0" :max-fraction-digits="2" fluid />
          </div>
        </div>
        <div class="grid-2">
          <div class="field">
            <label>Base IVA 0%</label>
            <InputNumber v-model="formManual.baseIva0" :min="0" :max-fraction-digits="2" fluid />
          </div>
          <div class="field">
            <label>Base no objeto</label>
            <InputNumber v-model="formManual.baseNoObjeto" :min="0" :max-fraction-digits="2" fluid />
          </div>
        </div>
        <div class="grid-2">
          <div class="field">
            <label>Retención IR</label>
            <InputNumber v-model="formManual.retencionIr" :min="0" :max-fraction-digits="2" fluid />
          </div>
          <div class="field">
            <label>Retención IVA</label>
            <InputNumber v-model="formManual.retencionIva" :min="0" :max-fraction-digits="2" fluid />
          </div>
        </div>
        <div class="grid-2">
          <div class="field">
            <label>Total a pagar (calculado: ${{ totalCalculado.toFixed(2) }})</label>
            <InputNumber v-model="formManual.total" :min="0" :max-fraction-digits="2" fluid />
          </div>
          <div class="field deducible-field">
            <label>&nbsp;</label>
            <div class="checkbox-line">
              <Checkbox v-model="formManual.deducible" :binary="true" input-id="deducible" />
              <label for="deducible">Es gasto deducible (declaración)</label>
            </div>
          </div>
        </div>

        <Message v-if="errorManual" severity="error" :closable="false">{{ errorManual }}</Message>
      </form>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogManual = false" />
        <Button :label="submitting ? 'Creando…' : 'Crear compra'" :loading="submitting"
                @click="crearManual" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: var(--sp-3); flex-wrap: wrap; }
.tabla :deep(tr) { cursor: pointer; }
.tabla :deep(.num) { text-align: right; font-family: var(--font-mono); }
.form-vert { display: flex; flex-direction: column; gap: var(--sp-3); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; font-size: var(--fs-sm); }
.archivo-info { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2);
                background: var(--color-marca-bg); border-radius: var(--radius-sm); }
.archivo-info .size { color: var(--color-muted); margin-left: auto; font-size: var(--fs-sm); }
.ayuda { color: var(--color-muted); margin: 0; font-size: var(--fs-sm); }
.deducible-field .checkbox-line { display: flex; align-items: center; gap: var(--sp-2);
                                  padding: var(--sp-2) 0; }
.full-w { width: 100%; }
</style>
