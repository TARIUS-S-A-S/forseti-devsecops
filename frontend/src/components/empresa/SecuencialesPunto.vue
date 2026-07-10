<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { empresaApi, type SecuencialResponse, type TipoComprobante } from '@/api/empresa'
import { mensajeDeError } from '@/composables/useApiError'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputNumber from 'primevue/inputnumber'
import Select from 'primevue/select'
import Message from 'primevue/message'

const props = defineProps<{
  empresaId: string
  establecimientoId: string
  puntoEmisionId: string
  puntoCodigo: string
}>()

const lista = ref<SecuencialResponse[]>([])
const cargando = ref(true)
const error = ref<string | null>(null)
const ok = ref<string | null>(null)

const dialog = ref(false)
const form = ref({
  tipoComprobante: 'FACTURA' as TipoComprobante,
  ambiente: 'PRUEBAS' as 'PRUEBAS' | 'PRODUCCION',
  ultimoNumeroEmitido: 0,
})
const guardando = ref(false)

const TIPOS_OPCIONES = [
  { label: 'Factura',                  value: 'FACTURA' },
  { label: 'Nota de Crédito',          value: 'NOTA_CREDITO' },
  { label: 'Nota de Débito',           value: 'NOTA_DEBITO' },
  { label: 'Retención',                value: 'RETENCION' },
  { label: 'Guía de Remisión',         value: 'GUIA_REMISION' },
  { label: 'Liquidación de Compra',    value: 'LIQUIDACION_COMPRA' },
]

const AMBIENTES_OPCIONES = [
  { label: 'PRUEBAS', value: 'PRUEBAS' },
  { label: 'PRODUCCIÓN', value: 'PRODUCCION' },
]

async function recargar() {
  cargando.value = true
  try {
    lista.value = await empresaApi.listarSecuenciales(
      props.empresaId, props.establecimientoId, props.puntoEmisionId)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo cargar la lista')
  } finally {
    cargando.value = false
  }
}

onMounted(recargar)

function abrirDialog(prefill?: SecuencialResponse) {
  form.value = {
    tipoComprobante: prefill?.tipoComprobante ?? 'FACTURA',
    ambiente: prefill?.ambiente ?? 'PRUEBAS',
    ultimoNumeroEmitido: prefill ? prefill.proximoNumero - 1 : 0,
  }
  error.value = null
  ok.value = null
  dialog.value = true
}

async function guardar() {
  guardando.value = true
  error.value = null
  try {
    const s = await empresaApi.configurarSecuencial(
      props.empresaId, props.establecimientoId, props.puntoEmisionId,
      form.value.tipoComprobante, form.value.ambiente, form.value.ultimoNumeroEmitido)
    ok.value = `Secuencial ${s.tipoComprobante} ${s.ambiente}: próximo número = ${s.proximoNumero}`
    dialog.value = false
    await recargar()
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo guardar')
  } finally {
    guardando.value = false
  }
}

const agrupadoPorAmbiente = computed(() => {
  const map: Record<string, SecuencialResponse[]> = { PRUEBAS: [], PRODUCCION: [] }
  for (const s of lista.value) {
    map[s.ambiente].push(s)
  }
  return map
})

function severityAmbiente(amb: string) {
  return amb === 'PRODUCCION' ? 'danger' : 'info'
}
</script>

<template>
  <div class="sec-block">
    <div class="head">
      <h4>Secuenciales del punto {{ puntoCodigo }}</h4>
      <Button label="Configurar secuencial" icon="pi pi-plus" size="small"
              severity="secondary" outlined @click="abrirDialog()" />
    </div>

    <Message v-if="ok" severity="success" :closable="true" @close="ok = null">{{ ok }}</Message>
    <Message v-if="error" severity="error" :closable="true" @close="error = null">{{ error }}</Message>

    <div v-if="cargando" class="cargando">Cargando…</div>
    <div v-else-if="lista.length === 0" class="vacio">
      Sin secuenciales configurados. Click en "Configurar secuencial".
    </div>
    <div v-else class="grids">
      <div v-for="(secs, amb) in agrupadoPorAmbiente" :key="amb" class="grid-amb">
        <h5><Tag :value="amb" :severity="severityAmbiente(String(amb))" /></h5>
        <table v-if="secs.length > 0" class="tabla">
          <thead>
            <tr><th>Tipo</th><th class="num">Próximo #</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="s in secs" :key="s.id">
              <td>{{ s.tipoComprobante.replace('_', ' ') }}</td>
              <td class="num mono">{{ s.proximoNumero }}</td>
              <td>
                <Button icon="pi pi-pencil" text size="small"
                        @click="abrirDialog(s)" title="Editar último número" />
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="vacio-amb">Sin secuenciales en este ambiente.</div>
      </div>
    </div>

    <Dialog v-model:visible="dialog" header="Configurar secuencial"
            modal :draggable="false" :style="{ width: '480px' }"
            :breakpoints="{ '768px': '92vw' }">
      <div class="form">
        <p class="info">
          Si la empresa ya emitía facturas con otro sistema antes, escribí el último número
          emitido. Forseti arranca desde el siguiente. Si arrancás desde cero (sin historial),
          dejá en 0 y la primera será 1.
        </p>
        <div class="field">
          <label>Tipo de comprobante</label>
          <Select v-model="form.tipoComprobante" :options="TIPOS_OPCIONES"
                  option-label="label" option-value="value" class="full-width" />
        </div>
        <div class="field">
          <label>Ambiente</label>
          <Select v-model="form.ambiente" :options="AMBIENTES_OPCIONES"
                  option-label="label" option-value="value" class="full-width" />
        </div>
        <div class="field">
          <label>Último número ya emitido</label>
          <InputNumber v-model="form.ultimoNumeroEmitido" :min="0" :max="999999999"
                       class="full-width" show-buttons />
          <small>El próximo será {{ form.ultimoNumeroEmitido + 1 }}.</small>
        </div>
        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      </div>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialog = false" />
        <Button label="Guardar" :loading="guardando" @click="guardar" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.sec-block { margin-top: var(--sp-3); padding: var(--sp-3); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--sp-2); gap: var(--sp-2); flex-wrap: wrap; }
.head h4 { margin: 0; font-family: var(--font-display); font-size: var(--fs-md); }
.cargando, .vacio { color: var(--color-muted); padding: var(--sp-2); font-size: var(--fs-sm); }
.grids { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.grid-amb h5 { margin: 0 0 var(--sp-2); }
.tabla { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
.tabla th { text-align: left; padding: var(--sp-1); color: var(--color-muted); font-size: var(--fs-xs); text-transform: uppercase; border-bottom: 1px solid var(--color-border); }
.tabla th.num, .tabla td.num { text-align: right; }
.tabla td { padding: var(--sp-1); border-bottom: 1px solid var(--color-border); }
.mono { font-family: var(--font-mono); font-weight: 600; }
.vacio-amb { color: var(--color-muted); font-size: var(--fs-sm); padding: var(--sp-2); }
.form { display: flex; flex-direction: column; gap: var(--sp-3); }
.info { color: var(--color-muted); font-size: var(--fs-sm); margin: 0; }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; }
.field small { color: var(--color-muted); }
.full-width { width: 100%; }

@media (max-width: 768px) {
  .grids { grid-template-columns: 1fr; }
}
</style>
