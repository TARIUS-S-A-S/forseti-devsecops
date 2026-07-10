<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { empresaApi, type Establecimiento, type PuntoEmision } from '@/api/empresa'
import {
  emisionApi,
  type Ambiente,
  type CodigoIva,
  type FormaPago,
  type TipoIdReceptor,
} from '@/api/emision'
import { mensajeDeError } from '@/composables/useApiError'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Select from 'primevue/select'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'

const router = useRouter()
const auth = useAuthStore()
const empresaId = computed(() => auth.empresaActivaId)

const establecimientos = ref<Establecimiento[]>([])
const puntos = ref<PuntoEmision[]>([])
const cargandoCatalogos = ref(true)
const errorCatalogo = ref<string | null>(null)
const errorEnvio = ref<string | null>(null)
const enviando = ref(false)

interface ItemForm {
  codigoPrincipal: string
  descripcion: string
  cantidad: number
  precioUnitario: number
  descuento: number
  codigoIva: CodigoIva
}

const form = reactive({
  establecimientoId: '',
  puntoEmisionId: '',
  ambiente: 'PRUEBAS' as Ambiente,
  receptor: {
    tipoId: '07' as TipoIdReceptor,  // default Consumidor Final
    identificacion: '9999999999999',
    razonSocial: 'CONSUMIDOR FINAL',
    direccion: '',
    email: '',
    telefono: '',
  },
  formaPago: '01' as FormaPago,
  plazoDias: 0,
})

const items = ref<ItemForm[]>([
  { codigoPrincipal: '', descripcion: '', cantidad: 1, precioUnitario: 0, descuento: 0, codigoIva: 'IVA_15' },
])

const opcionesTipoId: { label: string; value: TipoIdReceptor }[] = [
  { label: 'RUC (04)', value: '04' },
  { label: 'Cédula (05)', value: '05' },
  { label: 'Pasaporte (06)', value: '06' },
  { label: 'Consumidor Final (07)', value: '07' },
  { label: 'Identif. exterior (08)', value: '08' },
]

const opcionesIva: { label: string; value: CodigoIva }[] = [
  { label: 'IVA 15%', value: 'IVA_15' },
  { label: 'IVA 0%', value: 'IVA_0' },
  { label: 'No objeto', value: 'NO_OBJETO' },
  { label: 'Exento', value: 'EXENTO' },
]

const opcionesAmbiente: { label: string; value: Ambiente }[] = [
  { label: 'PRUEBAS (no genera factura real)', value: 'PRUEBAS' },
  { label: 'PRODUCCIÓN (factura real con efecto fiscal)', value: 'PRODUCCION' },
]

const opcionesFormaPago: { label: string; value: FormaPago }[] = [
  { label: '01 - Efectivo', value: '01' },
  { label: '16 - Tarjeta débito', value: '16' },
  { label: '17 - Dinero electrónico', value: '17' },
  { label: '19 - Tarjeta crédito', value: '19' },
  { label: '20 - Otros (sistema financiero)', value: '20' },
]

const puntosFiltrados = computed(() =>
  puntos.value.filter((p) => p.establecimientoId === form.establecimientoId)
)

const totales = computed(() => {
  let sub = 0, desc = 0, iva = 0
  for (const it of items.value) {
    const baseLinea = Math.max(0, it.cantidad * it.precioUnitario - it.descuento)
    const tasa = it.codigoIva === 'IVA_15' ? 0.15 : 0
    sub += baseLinea
    desc += it.descuento
    iva += baseLinea * tasa
  }
  const total = sub + iva
  return {
    subtotal: redondear(sub),
    descuento: redondear(desc),
    iva: redondear(iva),
    total: redondear(total),
  }
})

function redondear(n: number): number {
  return Math.round(n * 100) / 100
}

function agregarItem() {
  items.value.push({
    codigoPrincipal: '',
    descripcion: '',
    cantidad: 1,
    precioUnitario: 0,
    descuento: 0,
    codigoIva: 'IVA_15',
  })
}

function quitarItem(idx: number) {
  if (items.value.length > 1) items.value.splice(idx, 1)
}

onMounted(async () => {
  if (!empresaId.value) {
    errorCatalogo.value = 'No hay empresa activa'
    cargandoCatalogos.value = false
    return
  }
  try {
    establecimientos.value = await empresaApi.listarEstablecimientos(empresaId.value)
    if (establecimientos.value.length === 1) {
      form.establecimientoId = establecimientos.value[0].id
    }
    for (const e of establecimientos.value) {
      const ptos = await empresaApi.listarPuntos(empresaId.value, e.id)
      puntos.value.push(...ptos)
    }
    if (puntosFiltrados.value.length === 1) {
      form.puntoEmisionId = puntosFiltrados.value[0].id
    }
  } catch {
    errorCatalogo.value = 'No se pudieron cargar establecimientos y puntos'
  } finally {
    cargandoCatalogos.value = false
  }
})

async function emitir() {
  errorEnvio.value = null
  if (!form.establecimientoId || !form.puntoEmisionId) {
    errorEnvio.value = 'Seleccioná establecimiento y punto de emisión.'
    return
  }
  if (items.value.length === 0 || items.value.some(it => !it.codigoPrincipal || !it.descripcion)) {
    errorEnvio.value = 'Completá código y descripción de todos los ítems.'
    return
  }
  enviando.value = true
  try {
    const comprobante = await emisionApi.emitirFactura({
      establecimientoId: form.establecimientoId,
      puntoEmisionId: form.puntoEmisionId,
      receptor: { ...form.receptor },
      items: items.value.map(it => ({
        codigoPrincipal: it.codigoPrincipal,
        descripcion: it.descripcion,
        cantidad: it.cantidad,
        precioUnitario: it.precioUnitario,
        descuento: it.descuento,
        codigoIva: it.codigoIva,
      })),
      formaPago: form.formaPago,
      plazoDias: form.plazoDias,
    }, form.ambiente)
    router.push({ name: 'comprobante-detalle', params: { id: comprobante.id } })
  } catch (e) {
    errorEnvio.value = mensajeDeError(e, 'No se pudo emitir la factura')
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <div class="page-shell page-shell--form">
    <header class="page-head">
      <h1>Crear factura</h1>
      <p class="page-sub">Generá una factura electrónica para tu cliente. Se firma y se envía al SRI automáticamente.</p>
    </header>

    <Message v-if="errorCatalogo" severity="error" :closable="false">{{ errorCatalogo }}</Message>

    <template v-if="!cargandoCatalogos && !errorCatalogo">
      <Card class="card">
        <template #title>Punto de emisión</template>
        <template #content>
          <div class="grid-2">
            <div class="field">
              <label>Establecimiento</label>
              <Select v-model="form.establecimientoId"
                :options="establecimientos"
                option-label="codigo" option-value="id"
                placeholder="Elegí" fluid />
            </div>
            <div class="field">
              <label>Punto de emisión</label>
              <Select v-model="form.puntoEmisionId"
                :options="puntosFiltrados"
                option-label="codigo" option-value="id"
                placeholder="Elegí" :disabled="!form.establecimientoId" fluid />
            </div>
          </div>
          <div class="field">
            <label>Ambiente</label>
            <Select v-model="form.ambiente" :options="opcionesAmbiente"
              option-label="label" option-value="value" fluid />
          </div>
        </template>
      </Card>

      <Card class="card">
        <template #title>Receptor</template>
        <template #content>
          <div class="grid-2">
            <div class="field">
              <label>Tipo de identificación</label>
              <Select v-model="form.receptor.tipoId" :options="opcionesTipoId"
                option-label="label" option-value="value" fluid />
            </div>
            <div class="field">
              <label>Identificación</label>
              <InputText v-model="form.receptor.identificacion" maxlength="20" fluid />
            </div>
          </div>
          <div class="field">
            <label>Razón social / Nombres y apellidos</label>
            <InputText v-model="form.receptor.razonSocial" maxlength="300" fluid />
          </div>
          <div class="grid-2">
            <div class="field">
              <label>Email (opcional)</label>
              <InputText v-model="form.receptor.email" type="email" fluid />
            </div>
            <div class="field">
              <label>Teléfono (opcional)</label>
              <InputText v-model="form.receptor.telefono" fluid />
            </div>
          </div>
          <div class="field">
            <label>Dirección (opcional)</label>
            <InputText v-model="form.receptor.direccion" fluid />
          </div>
        </template>
      </Card>

      <Card class="card">
        <template #title>Ítems</template>
        <template #content>
          <div class="table-scroll">
            <table class="items-table">
              <thead>
                <tr>
                  <th class="col-cod">Código</th>
                  <th>Descripción</th>
                  <th class="col-num">Cantidad</th>
                  <th class="col-num">P. Unit.</th>
                  <th class="col-num">Desc.</th>
                  <th class="col-iva">IVA</th>
                  <th class="col-total">Subtotal</th>
                  <th class="col-x"></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(it, idx) in items" :key="idx">
                  <td><InputText v-model="it.codigoPrincipal" maxlength="25" fluid /></td>
                  <td><InputText v-model="it.descripcion" maxlength="300" fluid /></td>
                  <td><InputNumber v-model="it.cantidad" :min="0" :max-fraction-digits="6" fluid /></td>
                  <td><InputNumber v-model="it.precioUnitario" :min="0" :max-fraction-digits="6" fluid /></td>
                  <td><InputNumber v-model="it.descuento" :min="0" :max-fraction-digits="2" fluid /></td>
                  <td>
                    <Select v-model="it.codigoIva" :options="opcionesIva"
                      option-label="label" option-value="value" fluid />
                  </td>
                  <td class="col-total">
                    {{ redondear(Math.max(0, it.cantidad * it.precioUnitario - it.descuento)).toFixed(2) }}
                  </td>
                  <td>
                    <Button icon="pi pi-times" severity="danger" text size="small"
                      :disabled="items.length === 1" @click="quitarItem(idx)" />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <Button icon="pi pi-plus" label="Agregar ítem" severity="secondary" text @click="agregarItem" />
        </template>
      </Card>

      <Card class="card">
        <template #title>Totales</template>
        <template #content>
          <div class="totales">
            <div><span>Subtotal sin impuestos</span><strong>${{ totales.subtotal.toFixed(2) }}</strong></div>
            <div><span>Total descuento</span><strong>${{ totales.descuento.toFixed(2) }}</strong></div>
            <div><span>IVA</span><strong>${{ totales.iva.toFixed(2) }}</strong></div>
            <div class="total"><span>Importe total</span><strong>${{ totales.total.toFixed(2) }}</strong></div>
          </div>
          <div class="grid-2 mt">
            <div class="field">
              <label>Forma de pago</label>
              <Select v-model="form.formaPago" :options="opcionesFormaPago"
                option-label="label" option-value="value" fluid />
            </div>
            <div class="field">
              <label>Plazo (días, 0 = contado)</label>
              <InputNumber v-model="form.plazoDias" :min="0" fluid />
            </div>
          </div>
        </template>
      </Card>

      <Message v-if="errorEnvio" severity="error" :closable="false">{{ errorEnvio }}</Message>

      <div class="actions">
        <Button label="Emitir factura" icon="pi pi-send" icon-pos="right"
          :loading="enviando" @click="emitir" />
      </div>
    </template>

    <div v-else-if="cargandoCatalogos" class="loading">Cargando…</div>
  </div>
</template>

<style scoped>
.card { width: 100%; }
.field { display: flex; flex-direction: column; gap: var(--sp-2); margin-bottom: var(--sp-4); }
.field label { font-size: var(--fs-sm); font-weight: 500; }
.items-table { width: 100%; border-collapse: collapse; margin-bottom: var(--sp-4); }
.items-table th, .items-table td { padding: var(--sp-2); text-align: left; vertical-align: middle; font-size: var(--fs-sm); }
.items-table th { color: var(--color-muted); border-bottom: 1px solid var(--color-border); }
.col-cod { width: 110px; }
.col-num { width: 100px; }
.col-iva { width: 130px; }
.col-total { width: 90px; text-align: right; font-weight: 500; }
.col-x { width: 36px; }
.totales { display: flex; flex-direction: column; gap: var(--sp-2); }
.totales > div { display: flex; justify-content: space-between; padding: var(--sp-2) 0; border-bottom: 1px solid var(--color-border); }
.totales > div.total { border-bottom: none; padding-top: var(--sp-3); font-size: var(--fs-lg); }
.actions { display: flex; justify-content: flex-end; }
.loading { padding: var(--sp-8); text-align: center; color: var(--color-muted); }
.mt { margin-top: var(--sp-4); }
</style>
