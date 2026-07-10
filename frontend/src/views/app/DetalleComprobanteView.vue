<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  emisionApi,
  type ComprobanteDetalladoResponse,
  type EstadoComprobante,
} from '@/api/emision'
import { SEVERITY_POR_ESTADO } from '@/constants/comprobante'
import { mensajeDeError } from '@/composables/useApiError'
import Card from 'primevue/card'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Message from 'primevue/message'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Textarea from 'primevue/textarea'
import Dialog from 'primevue/dialog'

const route = useRoute()
const router = useRouter()
const id = computed(() => String(route.params.id))

const datos = ref<ComprobanteDetalladoResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const mostrarCancelar = ref(false)
const motivoCancelar = ref('')
const cancelando = ref(false)
const errorCancelar = ref<string | null>(null)

const puedeCancelar = computed(() => {
  if (!datos.value) return false
  return ['BORRADOR', 'FIRMADA', 'DEVUELTA'].includes(datos.value.cabecera.estado)
})

async function cargar() {
  loading.value = true
  error.value = null
  try {
    datos.value = await emisionApi.obtener(id.value)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo cargar el comprobante')
  } finally {
    loading.value = false
  }
}

async function confirmarCancelar() {
  if (!datos.value) return
  cancelando.value = true
  errorCancelar.value = null
  try {
    await emisionApi.cancelar(datos.value.cabecera.id, motivoCancelar.value.trim() || undefined)
    mostrarCancelar.value = false
    motivoCancelar.value = ''
    await cargar()
  } catch (e) {
    errorCancelar.value = mensajeDeError(e, 'No se pudo cancelar la factura')
  } finally {
    cancelando.value = false
  }
}

function formatFecha(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('es-EC')
}

function formatFechaCorta(iso: string): string {
  return new Date(iso).toLocaleDateString('es-EC')
}

// ─── Nota de Crédito ─────────────────────────────────────────────────────
const mostrarNC = ref(false)
const motivoNC = ref('')
const emitiendoNC = ref(false)
const errorNC = ref<string | null>(null)

function abrirDialogNC() {
  motivoNC.value = ''
  errorNC.value = null
  mostrarNC.value = true
}

async function confirmarNC() {
  if (!datos.value) return
  if (motivoNC.value.trim().length < 5) {
    errorNC.value = 'El motivo debe tener al menos 5 caracteres'
    return
  }
  emitiendoNC.value = true
  errorNC.value = null
  try {
    const c = datos.value.cabecera
    const det = datos.value.detalles
    const ambiente = (c.ambiente === 'PRODUCCION' ? 'PRODUCCION' : 'PRUEBAS') as 'PRUEBAS' | 'PRODUCCION'
    const nc = await emisionApi.emitirNotaCredito({
      establecimientoId: c.establecimientoId,
      puntoEmisionId: c.puntoEmisionId,
      receptor: {
        tipoId: c.receptor.tipoId as '04' | '05' | '06' | '07' | '08',
        identificacion: c.receptor.identificacion,
        razonSocial: c.receptor.razonSocial,
        direccion: c.receptor.direccion ?? undefined,
        email: c.receptor.email ?? undefined,
        telefono: c.receptor.telefono ?? undefined,
      },
      items: det.map(d => ({
        codigoPrincipal: d.codigoPrincipal,
        descripcion: d.descripcion,
        cantidad: d.cantidad,
        precioUnitario: d.precioUnitario,
        descuento: d.descuento,
        codigoIva: d.codigoIva === 'IVA_15' ? 'IVA_15' : 'IVA_0',
      })),
      docModificadoTipo: '01',
      docModificadoNumero: c.numeroComprobante,
      docModificadoFecha: c.fechaEmision,
      motivo: motivoNC.value.trim(),
    }, ambiente)
    mostrarNC.value = false
    router.push({ name: 'comprobante-detalle', params: { id: nc.id } })
  } catch (e) {
    errorNC.value = mensajeDeError(e, 'No se pudo emitir la NC')
  } finally {
    emitiendoNC.value = false
  }
}

function urlRide(): string {
  // El backend devuelve PDF inline con disposition correcta.
  return `/api/v1/comprobantes/${id.value}/ride.pdf`
}

function verRide() {
  // Abre el PDF en una pestaña nueva — el navegador lo renderiza inline.
  window.open(urlRide(), '_blank')
}

async function descargarRide() {
  // Fuerza la descarga creando un <a download>.
  if (!datos.value) return
  try {
    const resp = await fetch(urlRide(), { credentials: 'include' })
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    const blob = await resp.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `RIDE-${datos.value.cabecera.numeroComprobante.replace(/-/g, '_')}.pdf`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(a.href)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo descargar el RIDE')
  }
}

onMounted(cargar)
</script>

<template>
  <div class="page-shell">
    <div class="head">
      <Button icon="pi pi-arrow-left" text @click="router.push({ name: 'comprobantes' })" />
      <h1 v-if="datos">Factura {{ datos.cabecera.numeroComprobante }}</h1>
      <h1 v-else>Comprobante</h1>
      <div class="head-actions" v-if="datos && datos.cabecera.estado !== 'BORRADOR'">
        <Button label="Ver RIDE" icon="pi pi-file-pdf" severity="secondary" outlined
                @click="verRide" />
        <Button label="Descargar RIDE" icon="pi pi-download"
                @click="descargarRide" />
        <Button v-if="datos.cabecera.estado === 'AUTORIZADA' && datos.cabecera.tipoComprobante === 'FACTURA'"
                label="Emitir Nota de Crédito" icon="pi pi-undo"
                severity="warn" @click="abrirDialogNC" />
      </div>
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <template v-if="datos">
      <Card class="card">
        <template #title>
          <div class="title-row">
            <span>Estado</span>
            <Tag :value="datos.cabecera.estado"
              :severity="SEVERITY_POR_ESTADO[datos.cabecera.estado]" />
          </div>
        </template>
        <template #content>
          <div class="grid">
            <div><label>Clave de acceso</label><code>{{ datos.cabecera.claveAcceso }}</code></div>
            <div>
<label>Ambiente</label>
              <Tag :value="datos.cabecera.ambiente"
                :severity="datos.cabecera.ambiente === 'PRODUCCION' ? 'warn' : 'info'" />
            </div>
            <div><label>Fecha emisión</label><span>{{ formatFechaCorta(datos.cabecera.fechaEmision) }}</span></div>
            <div v-if="datos.cabecera.numeroAutorizacion">
              <label>Número autorización</label>
              <code>{{ datos.cabecera.numeroAutorizacion }}</code>
            </div>
            <div v-if="datos.cabecera.fechaAutorizacion">
              <label>Fecha autorización</label>
              <span>{{ formatFecha(datos.cabecera.fechaAutorizacion) }}</span>
            </div>
            <div v-if="datos.cabecera.intentosEnvio > 0">
              <label>Intentos al SRI</label><span>{{ datos.cabecera.intentosEnvio }}</span>
            </div>
          </div>
          <Message v-if="datos.cabecera.mensajeSri" class="mt"
            :severity="datos.cabecera.estado === 'AUTORIZADA' ? 'success' : 'warn'"
            :closable="false">
            <strong v-if="datos.cabecera.codigoErrorSri">[{{ datos.cabecera.codigoErrorSri }}]</strong>
            {{ datos.cabecera.mensajeSri }}
          </Message>
        </template>
      </Card>

      <Card class="card">
        <template #title>Receptor</template>
        <template #content>
          <div class="grid">
            <div><label>Razón social</label><span>{{ datos.cabecera.receptor.razonSocial }}</span></div>
            <div><label>Identificación</label><span>{{ datos.cabecera.receptor.identificacion }}</span></div>
            <div v-if="datos.cabecera.receptor.direccion">
              <label>Dirección</label><span>{{ datos.cabecera.receptor.direccion }}</span>
            </div>
            <div v-if="datos.cabecera.receptor.email">
              <label>Email</label><span>{{ datos.cabecera.receptor.email }}</span>
            </div>
          </div>
        </template>
      </Card>

      <Card class="card">
        <template #title>Ítems</template>
        <template #content>
          <DataTable :value="datos.detalles" striped-rows>
            <Column field="codigoPrincipal" header="Código" />
            <Column field="descripcion" header="Descripción" />
            <Column field="cantidad" header="Cant." class="num" />
            <Column field="precioUnitario" header="P. Unit." class="num">
              <template #body="{ data }">${{ data.precioUnitario }}</template>
            </Column>
            <Column field="tarifa" header="IVA %" class="num">
              <template #body="{ data }">{{ data.tarifa }}%</template>
            </Column>
            <Column field="precioTotalSinImpuesto" header="Subtotal" class="num">
              <template #body="{ data }">${{ data.precioTotalSinImpuesto }}</template>
            </Column>
            <Column field="valorImpuesto" header="IVA" class="num">
              <template #body="{ data }">${{ data.valorImpuesto }}</template>
            </Column>
          </DataTable>
        </template>
      </Card>

      <Card class="card">
        <template #title>Totales</template>
        <template #content>
          <div class="totales">
            <div><span>Subtotal sin impuestos</span><strong>${{ datos.cabecera.totales.subtotalSinImpuestos }}</strong></div>
            <div><span>Descuento</span><strong>${{ datos.cabecera.totales.totalDescuento }}</strong></div>
            <div><span>IVA</span><strong>${{ datos.cabecera.totales.totalIva }}</strong></div>
            <div class="total"><span>Importe total</span><strong>${{ datos.cabecera.totales.importeTotal }}</strong></div>
          </div>
        </template>
      </Card>

      <Card class="card">
        <template #title>Historia</template>
        <template #content>
          <ul class="historia">
            <li v-for="(ev, idx) in datos.historia" :key="idx">
              <div class="hora">{{ formatFecha(ev.cuando) }}</div>
              <div class="trans">
                <span v-if="ev.estadoAnterior" class="from">{{ ev.estadoAnterior }}</span>
                <i v-if="ev.estadoAnterior" class="pi pi-arrow-right" />
                <Tag :value="ev.estadoNuevo" :severity="SEVERITY_POR_ESTADO[ev.estadoNuevo as EstadoComprobante]" />
              </div>
              <div class="msg" v-if="ev.mensaje">{{ ev.mensaje }}</div>
            </li>
          </ul>
        </template>
      </Card>

      <div class="actions" v-if="puedeCancelar">
        <Button label="Cancelar comprobante" icon="pi pi-times"
          severity="danger" outlined @click="mostrarCancelar = true" />
      </div>

      <Dialog v-model:visible="mostrarCancelar" header="Cancelar comprobante"
        modal :style="{ width: '480px' }" :breakpoints="{ '768px': '92vw' }">
        <p>Vas a marcar este comprobante como <strong>ABANDONADA</strong>. No se reenviará al SRI.</p>
        <p class="hint">
          Esta acción NO anula una factura ya autorizada por el SRI — para eso usá el portal SRI.
        </p>
        <div class="field mt">
          <label>Motivo (opcional)</label>
          <Textarea v-model="motivoCancelar" rows="3" fluid />
        </div>
        <Message v-if="errorCancelar" severity="error" :closable="false">{{ errorCancelar }}</Message>
        <template #footer>
          <Button label="Cerrar" text @click="mostrarCancelar = false" />
          <Button label="Cancelar comprobante" severity="danger"
            :loading="cancelando" @click="confirmarCancelar" />
        </template>
      </Dialog>

      <Dialog v-model:visible="mostrarNC" header="Emitir Nota de Crédito"
              modal :draggable="false" :style="{ width: '500px' }"
              :breakpoints="{ '768px': '92vw' }">
        <div class="nc-form">
          <p class="nc-info">
            Vas a emitir una <strong>NC sobre la factura {{ datos.cabecera.numeroComprobante }}</strong>.
            Los ítems y receptor se copian de la factura original (esta es la NC "anulación total" más
            común). Para una NC parcial, cancelá acá y emitila desde la sección Comprobantes con el
            flujo completo (Sprint 5).
          </p>
          <div class="field">
            <label for="motivo-nc">Motivo de la NC <span class="req">*</span></label>
            <Textarea id="motivo-nc" v-model="motivoNC" rows="3" auto-resize
              placeholder="Ej: Devolución por defecto del producto / Anulación por error de facturación" />
            <small>Mínimo 5 caracteres. Aparecerá en el XML SRI.</small>
          </div>
          <Message v-if="errorNC" severity="error" :closable="false">{{ errorNC }}</Message>
        </div>
        <template #footer>
          <Button label="Cancelar" severity="secondary" text @click="mostrarNC = false" />
          <Button :label="emitiendoNC ? 'Emitiendo…' : 'Emitir NC'" severity="warn"
            :loading="emitiendoNC" :disabled="motivoNC.trim().length < 5"
            @click="confirmarNC" />
        </template>
      </Dialog>
    </template>

    <div v-else-if="loading" class="loading">Cargando…</div>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-2); flex-wrap: wrap; }
.head h1 { font-size: var(--fs-2xl); margin: 0; }
.head-actions { margin-left: auto; display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.card { width: 100%; }
.title-row { display: flex; align-items: center; gap: var(--sp-3); justify-content: space-between; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }
.grid > div { display: flex; flex-direction: column; gap: var(--sp-1); }
.grid label { font-size: var(--fs-xs); color: var(--color-muted); text-transform: uppercase; letter-spacing: 0.05em; }
.grid code { font-family: var(--font-mono); font-size: var(--fs-sm); word-break: break-all; }
.totales > div { display: flex; justify-content: space-between; padding: var(--sp-2) 0; border-bottom: 1px solid var(--color-border); }
.totales > div.total { border-bottom: none; font-size: var(--fs-lg); padding-top: var(--sp-3); }
.historia { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--sp-3); }
.historia li { padding: var(--sp-3); border-left: 2px solid var(--color-border); }
.historia .hora { font-size: var(--fs-xs); color: var(--color-muted); margin-bottom: var(--sp-1); }
.historia .trans { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-2); }
.historia .trans .from { color: var(--color-muted); font-size: var(--fs-sm); }
.historia .msg { font-size: var(--fs-sm); }
.actions { display: flex; justify-content: flex-end; padding: var(--sp-4) 0; }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-size: var(--fs-sm); font-weight: 500; }
.hint { color: var(--color-muted); font-size: var(--fs-sm); }
.loading { padding: var(--sp-8); text-align: center; color: var(--color-muted); }
.mt { margin-top: var(--sp-4); }
:deep(.num) { text-align: right; }

@media (max-width: 768px) {
  .grid { grid-template-columns: 1fr; }
  .head h1 { font-size: var(--fs-xl); width: 100%; }
  .head-actions { width: 100%; justify-content: flex-end; }
}
</style>
