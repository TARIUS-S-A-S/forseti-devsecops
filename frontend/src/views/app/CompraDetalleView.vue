<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { comprasApi, type CompraResponse, type AdjuntoResponse } from '@/api/compras'
import { mensajeDeError } from '@/composables/useApiError'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Textarea from 'primevue/textarea'
import DatePicker from 'primevue/datepicker'
import Select from 'primevue/select'
import FileUpload from 'primevue/fileupload'
import Message from 'primevue/message'

const route = useRoute()
const router = useRouter()
const id = computed(() => String(route.params.id))

const compra = ref<CompraResponse | null>(null)
const adjuntos = ref<AdjuntoResponse[]>([])
const cargando = ref(true)
const error = ref<string | null>(null)
const ok = ref<string | null>(null)

const dialogAnular = ref(false)
const motivoAnular = ref('')
const anulando = ref(false)
const errorAnular = ref<string | null>(null)

const dialogPagar = ref(false)
const fechaPago = ref(new Date().toISOString().slice(0, 10))
const formaPago = ref<string>('20')
const pagando = ref(false)
const errorPagar = ref<string | null>(null)

const subiendoAdjunto = ref(false)
const errorAdjunto = ref<string | null>(null)

const formaPagoOpts = [
  { label: '01 — Efectivo', value: '01' },
  { label: '16 — Tarjeta débito', value: '16' },
  { label: '17 — Dinero electrónico', value: '17' },
  { label: '19 — Tarjeta crédito', value: '19' },
  { label: '20 — Otros sistema financiero', value: '20' },
]

async function cargar() {
  cargando.value = true
  error.value = null
  try {
    const [c, ads] = await Promise.all([
      comprasApi.obtener(id.value),
      comprasApi.listarAdjuntos(id.value),
    ])
    compra.value = c
    adjuntos.value = ads
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo cargar la compra')
  } finally {
    cargando.value = false
  }
}

onMounted(cargar)

async function confirmarAnular() {
  if (motivoAnular.value.trim().length < 3) {
    errorAnular.value = 'El motivo debe tener al menos 3 caracteres'
    return
  }
  anulando.value = true
  errorAnular.value = null
  try {
    const c = await comprasApi.anular(id.value, motivoAnular.value.trim())
    compra.value = c
    dialogAnular.value = false
    motivoAnular.value = ''
    ok.value = 'Compra anulada. Queda visible para auditoría pero fuera de totales.'
  } catch (e) {
    errorAnular.value = mensajeDeError(e, 'No se pudo anular')
  } finally {
    anulando.value = false
  }
}

async function confirmarPagar() {
  pagando.value = true
  errorPagar.value = null
  try {
    const c = await comprasApi.marcarPagado(id.value, fechaPago.value, formaPago.value)
    compra.value = c
    dialogPagar.value = false
    ok.value = 'Compra marcada como pagada.'
  } catch (e) {
    errorPagar.value = mensajeDeError(e, 'No se pudo marcar pagado')
  } finally {
    pagando.value = false
  }
}

async function onAdjuntoSelect(event: { files: File[] }) {
  const file = event.files[0]
  if (!file) return
  subiendoAdjunto.value = true
  errorAdjunto.value = null
  try {
    await comprasApi.subirAdjunto(id.value, file)
    await cargar()
    ok.value = `Adjunto "${file.name}" subido.`
  } catch (e) {
    errorAdjunto.value = mensajeDeError(e, 'No se pudo subir el adjunto')
  } finally {
    subiendoAdjunto.value = false
  }
}

function descargarAdjunto(a: AdjuntoResponse) {
  window.open(comprasApi.urlAdjunto(id.value, a.id), '_blank')
}

function formatFecha(s: string | null) {
  if (!s) return '—'
  return new Date(s).toLocaleDateString('es-EC')
}

function formatFechaHora(s: string | null) {
  if (!s) return '—'
  return new Date(s).toLocaleString('es-EC')
}

function severityEstadoPago(estado: string | undefined) {
  if (estado === 'PAGADO') return 'success'
  if (estado === 'PARCIAL') return 'warn'
  return 'secondary'
}
</script>

<template>
  <div class="page-shell">
    <div class="head">
      <Button icon="pi pi-arrow-left" text @click="router.push({ name: 'compras' })" />
      <h1 v-if="compra">Compra {{ compra.numeroDocumento }}</h1>
      <h1 v-else>Compra</h1>
      <div class="head-actions" v-if="compra && !compra.anulada">
        <Button v-if="compra.estadoPago !== 'PAGADO'" label="Marcar pagado" icon="pi pi-dollar"
                @click="dialogPagar = true" />
        <Button label="Anular" icon="pi pi-times" severity="danger" outlined
                @click="dialogAnular = true" />
      </div>
    </div>

    <Message v-if="ok" severity="success" :closable="true" @close="ok = null">{{ ok }}</Message>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <template v-if="compra">
      <Message v-if="compra.anulada" severity="warn" :closable="false">
        <strong>Compra anulada</strong> el {{ formatFechaHora(compra.anuladaAt) }} —
        motivo: {{ compra.motivoAnulacion }}. No entra en totales ni declaraciones.
      </Message>

      <Card class="card">
        <template #title>
          <div class="title-row">
            <span>Datos del comprobante</span>
            <Tag :value="compra.tipoDocumento.replace('_', ' ')" severity="info" />
            <Tag :value="compra.origen" :severity="compra.origen === 'XML' ? 'info' : 'secondary'" />
            <Tag :value="compra.estadoPago" :severity="severityEstadoPago(compra.estadoPago)" />
          </div>
        </template>
        <template #content>
          <div class="grid">
            <div><label>Fecha emisión</label><span>{{ formatFecha(compra.fechaEmision) }}</span></div>
            <div><label>Número</label><span class="mono">{{ compra.numeroDocumento }}</span></div>
            <div><label>Proveedor</label><span>{{ compra.proveedorRazonSocial }}</span></div>
            <div>
<label>{{ compra.proveedorTipoId === '04' ? 'RUC' : 'ID' }}</label>
                 <span class="mono">{{ compra.proveedorIdentificacion }}</span>
</div>
            <div v-if="compra.claveAcceso">
              <label>Clave acceso SRI</label>
              <code class="mono">{{ compra.claveAcceso }}</code>
            </div>
            <div v-if="compra.categoriaNombre">
              <label>Categoría</label><span>{{ compra.categoriaNombre }}</span>
            </div>
            <div><label>Deducible</label><span>{{ compra.deducible ? 'Sí' : 'No' }}</span></div>
            <div v-if="compra.fechaPago">
              <label>Pagado</label>
              <span>{{ formatFecha(compra.fechaPago) }} ({{ compra.formaPago }})</span>
            </div>
          </div>
          <div class="concepto">
            <label>Concepto</label>
            <p>{{ compra.concepto }}</p>
          </div>
        </template>
      </Card>

      <Card class="card">
        <template #title>Bases e impuestos</template>
        <template #content>
          <div class="totales">
            <div><span>Base IVA 15%</span><strong>${{ compra.baseIva15 }}</strong></div>
            <div><span>Base IVA 0%</span><strong>${{ compra.baseIva0 }}</strong></div>
            <div v-if="Number(compra.baseNoObjeto) > 0">
              <span>Base no objeto</span><strong>${{ compra.baseNoObjeto }}</strong>
            </div>
            <div v-if="Number(compra.baseExento) > 0">
              <span>Base exento</span><strong>${{ compra.baseExento }}</strong>
            </div>
            <div><span>IVA 15%</span><strong>${{ compra.valorIva15 }}</strong></div>
            <div v-if="Number(compra.retencionIr) > 0">
              <span>Retención IR</span><strong>−${{ compra.retencionIr }}</strong>
            </div>
            <div v-if="Number(compra.retencionIva) > 0">
              <span>Retención IVA</span><strong>−${{ compra.retencionIva }}</strong>
            </div>
            <div class="total">
              <span>Total a pagar</span>
              <strong>${{ compra.total }}</strong>
            </div>
          </div>
        </template>
      </Card>

      <Card class="card">
        <template #title>Adjuntos ({{ adjuntos.length }})</template>
        <template #content>
          <div class="upload-line">
            <FileUpload mode="basic" accept=".pdf,.xml,application/pdf,application/xml"
                        :max-file-size="10 * 1024 * 1024"
                        choose-label="Subir adjunto" :auto="true"
                        :custom-upload="true" @select="onAdjuntoSelect" />
            <small v-if="subiendoAdjunto">Subiendo…</small>
          </div>
          <Message v-if="errorAdjunto" severity="error" :closable="true" @close="errorAdjunto = null">
            {{ errorAdjunto }}
          </Message>
          <ul v-if="adjuntos.length > 0" class="adjuntos">
            <li v-for="a in adjuntos" :key="a.id">
              <i class="pi" :class="a.mimeTypeReal === 'application/pdf' ? 'pi-file-pdf' : 'pi-file'" />
              <span class="nombre">{{ a.nombreOriginal }}</span>
              <span class="meta">{{ Math.round(a.tamanoBytes / 1024) }} KB · {{ a.mimeTypeReal }}</span>
              <Button icon="pi pi-download" text rounded size="small" @click="descargarAdjunto(a)" />
            </li>
          </ul>
          <p v-else class="empty">Sin adjuntos. Subí el PDF o XML del comprobante para tener todo junto.</p>
        </template>
      </Card>
    </template>

    <div v-else-if="cargando" class="loading">Cargando…</div>

    <!-- Dialog Anular -->
    <Dialog v-model:visible="dialogAnular" modal :draggable="false"
            header="Anular compra"
            :style="{ width: '480px' }" :breakpoints="{ '768px': '92vw' }">
      <p>
        Anular <strong>NO borra</strong> la compra — queda visible para auditoría pero
        sale de totales y de la declaración. Esta acción NO se puede deshacer.
      </p>
      <div class="field mt">
        <label>Motivo (mínimo 3 caracteres) *</label>
        <Textarea v-model="motivoAnular" rows="3" auto-resize maxlength="500"
                  placeholder="Ej: factura duplicada / cargada por error" />
      </div>
      <Message v-if="errorAnular" severity="error" :closable="false">{{ errorAnular }}</Message>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogAnular = false" />
        <Button label="Anular" severity="danger" :loading="anulando"
                :disabled="motivoAnular.trim().length < 3" @click="confirmarAnular" />
      </template>
    </Dialog>

    <!-- Dialog Marcar Pagado -->
    <Dialog v-model:visible="dialogPagar" modal :draggable="false"
            header="Marcar como pagado"
            :style="{ width: '420px' }" :breakpoints="{ '768px': '92vw' }">
      <div class="form-vert">
        <div class="field">
          <label>Fecha del pago</label>
          <DatePicker v-model:value="fechaPago" date-format="yy-mm-dd" fluid />
        </div>
        <div class="field">
          <label>Forma de pago</label>
          <Select v-model="formaPago" :options="formaPagoOpts"
                  option-label="label" option-value="value" fluid />
        </div>
        <Message v-if="errorPagar" severity="error" :closable="false">{{ errorPagar }}</Message>
      </div>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogPagar = false" />
        <Button label="Confirmar pago" :loading="pagando" @click="confirmarPagar" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.head h1 { font-size: var(--fs-2xl); margin: 0; }
.head-actions { margin-left: auto; display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.card { width: 100%; }
.title-row { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }
.grid > div { display: flex; flex-direction: column; gap: var(--sp-1); }
.grid label { font-size: var(--fs-xs); color: var(--color-muted); text-transform: uppercase; letter-spacing: 0.05em; }
.mono { font-family: var(--font-mono); word-break: break-all; }
.concepto { margin-top: var(--sp-4); }
.concepto label { font-size: var(--fs-xs); color: var(--color-muted); text-transform: uppercase; letter-spacing: 0.05em; }
.concepto p { margin: var(--sp-1) 0 0; }
.totales > div { display: flex; justify-content: space-between; padding: var(--sp-2) 0; border-bottom: 1px solid var(--color-border); }
.totales > div.total { border-bottom: none; padding-top: var(--sp-3); font-size: var(--fs-lg); font-weight: 600; }
.upload-line { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-3); }
.adjuntos { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--sp-2); }
.adjuntos li { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2);
               background: var(--color-bg); border-radius: var(--radius-sm); }
.adjuntos .nombre { flex: 1; font-weight: 500; }
.adjuntos .meta { color: var(--color-muted); font-size: var(--fs-xs); }
.empty { color: var(--color-muted); font-size: var(--fs-sm); }
.loading { padding: var(--sp-8); text-align: center; color: var(--color-muted); }
.form-vert { display: flex; flex-direction: column; gap: var(--sp-3); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; font-size: var(--fs-sm); }
.mt { margin-top: var(--sp-3); }

@media (max-width: 768px) {
  .grid { grid-template-columns: 1fr; }
  .head h1 { font-size: var(--fs-xl); }
}
</style>
