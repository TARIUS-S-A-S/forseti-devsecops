<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { empresaApi, type Empresa, type PerfilTributario } from '@/api/empresa'
import Select from 'primevue/select'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Message from 'primevue/message'

const auth = useAuthStore()
const empresaId = computed(() => auth.empresaActivaId)

const empresa = ref<Empresa | null>(null)
const perfilActual = ref<PerfilTributario | null>(null)
const historial = ref<PerfilTributario[]>([])
const cargando = ref(true)
const guardando = ref(false)
const error = ref<string | null>(null)
const exito = ref<string | null>(null)

const form = reactive({
  regimenTributario: 'RIMPE_EMPRENDEDOR' as PerfilTributario['regimenTributario'],
  periodicidadIva: 'SEMESTRAL' as PerfilTributario['periodicidadIva'],
  obligadoContabilidad: false,
  agenteRetencion: false,
  vigenteDesde: '',
  motivoCambio: '',
})

const regimenes = [
  { label: 'RIMPE Negocio Popular', value: 'RIMPE_NP' },
  { label: 'RIMPE Emprendedor', value: 'RIMPE_EMPRENDEDOR' },
  { label: 'Régimen General', value: 'GENERAL' },
]
const periodicidades = [
  { label: 'Mensual', value: 'MENSUAL' },
  { label: 'Semestral', value: 'SEMESTRAL' },
  { label: 'No aplica', value: 'NO_APLICA' },
]

onMounted(async () => {
  if (!empresaId.value) {
    error.value = 'No hay empresa activa'
    cargando.value = false
    return
  }
  await recargar()
})

async function recargar() {
  if (!empresaId.value) return
  cargando.value = true
  try {
    empresa.value = await empresaApi.obtener(empresaId.value)
    perfilActual.value = await empresaApi.perfilVigente(empresaId.value)
    historial.value = await empresaApi.historialPerfil(empresaId.value)
    form.regimenTributario = perfilActual.value.regimenTributario
    form.periodicidadIva = perfilActual.value.periodicidadIva
    form.obligadoContabilidad = perfilActual.value.obligadoContabilidad
    form.agenteRetencion = perfilActual.value.agenteRetencion
    form.vigenteDesde = new Date().toISOString().slice(0, 10)
    form.motivoCambio = ''
  } catch {
    error.value = 'No se pudo cargar el perfil'
  } finally {
    cargando.value = false
  }
}

async function guardar() {
  if (!empresaId.value) return
  guardando.value = true
  error.value = null
  exito.value = null
  try {
    await empresaApi.actualizarPerfil(empresaId.value, {
      regimenTributario: form.regimenTributario,
      periodicidadIva: form.periodicidadIva,
      obligadoContabilidad: form.obligadoContabilidad,
      agenteRetencion: form.agenteRetencion,
      vigenteDesde: form.vigenteDesde,
      motivoCambio: form.motivoCambio,
    })
    exito.value = 'Perfil actualizado. La vigencia anterior queda en historial.'
    await recargar()
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'No se pudo actualizar el perfil'
  } finally {
    guardando.value = false
  }
}
</script>

<template>
  <div class="perfil">
    <header class="page-head">
      <h1>Perfil tributario</h1>
      <p class="subtitulo" v-if="empresa">
        <strong>{{ empresa.razonSocial }}</strong> · RUC {{ empresa.ruc }}
      </p>
    </header>

    <Message v-if="error" severity="error">{{ error }}</Message>
    <Message v-if="exito" severity="success">{{ exito }}</Message>

    <div v-if="cargando" class="loading">Cargando…</div>

    <template v-else>
      <Message severity="info" :closable="false" class="explicacion">
        Cambiar régimen / periodicidad <strong>NO pisa</strong> el historial: se cierra la vigencia
        anterior y se crea una nueva. Las declaraciones de períodos pasados usan el perfil
        vigente a esa fecha.
      </Message>

      <form class="form" @submit.prevent="guardar">
        <fieldset>
          <legend>Vigencia nueva</legend>

          <div class="field">
            <label for="regimen">Régimen</label>
            <Select id="regimen" v-model="form.regimenTributario" :options="regimenes"
                    option-label="label" option-value="value" />
          </div>

          <div class="field">
            <label for="per">Periodicidad de IVA</label>
            <Select id="per" v-model="form.periodicidadIva" :options="periodicidades"
                    option-label="label" option-value="value" />
          </div>

          <div class="field-row">
            <div class="checkbox-row">
              <Checkbox v-model="form.obligadoContabilidad" :binary="true" input-id="ob-cont" />
              <label for="ob-cont">Obligada a llevar contabilidad</label>
            </div>
            <div class="checkbox-row">
              <Checkbox v-model="form.agenteRetencion" :binary="true" input-id="ret" />
              <label for="ret">Agente de retención</label>
            </div>
          </div>

          <div class="field">
            <label for="desde">Vigente desde</label>
            <InputText id="desde" v-model="form.vigenteDesde" type="date" />
            <small>Por default hoy. Si declaraste antes de esta fecha con el régimen anterior, dejala así.</small>
          </div>

          <div class="field">
            <label for="motivo">Motivo del cambio (opcional)</label>
            <InputText id="motivo" v-model="form.motivoCambio"
                       placeholder="Ej: paso a régimen general por ingresos > umbral" />
          </div>
        </fieldset>

        <div class="actions">
          <Button type="submit" :loading="guardando" label="Crear vigencia nueva" icon="pi pi-check" />
        </div>
      </form>

      <section class="historial">
        <h2>Historial de vigencias</h2>
        <ul>
          <li v-for="h in historial" :key="h.id" :class="{ actual: !h.vigenteHasta }">
            <div class="vig-fecha">
              {{ h.vigenteDesde }} → {{ h.vigenteHasta ?? 'actual' }}
            </div>
            <div class="vig-datos">
              <span>{{ h.regimenTributario }}</span>
              ·
              <span>IVA {{ h.periodicidadIva }}</span>
              <span v-if="h.obligadoContabilidad"> · Obligada a contabilidad</span>
              <span v-if="h.agenteRetencion"> · Agente de retención</span>
            </div>
            <div v-if="h.motivoCambio" class="motivo">{{ h.motivoCambio }}</div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.perfil { max-width: 760px; margin: 0 auto; }
.page-head { margin-bottom: var(--sp-4); }
.page-head h1 { font-family: var(--font-display); }
.subtitulo { color: var(--color-muted); }
.loading { text-align: center; padding: var(--sp-8); color: var(--color-muted); }
.explicacion { margin-bottom: var(--sp-4); }
.form { display: flex; flex-direction: column; gap: var(--sp-4); margin-bottom: var(--sp-8); }
fieldset { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--sp-5); display: flex; flex-direction: column; gap: var(--sp-4); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; font-size: var(--fs-sm); }
.field small { color: var(--color-muted); font-size: var(--fs-xs); }
.field-row { display: flex; gap: var(--sp-6); }
.checkbox-row { display: flex; align-items: center; gap: var(--sp-2); }
.actions { display: flex; justify-content: flex-end; }
.historial { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--sp-5); }
.historial h2 { margin-bottom: var(--sp-4); font-family: var(--font-display); }
.historial ul { list-style: none; padding: 0; display: flex; flex-direction: column; gap: var(--sp-3); }
.historial li { padding: var(--sp-3); border-left: 3px solid var(--color-border); padding-left: var(--sp-4); }
.historial li.actual { border-left-color: var(--color-marca); }
.vig-fecha { font-family: var(--font-mono); font-weight: 600; color: var(--color-ink); }
.vig-datos { color: var(--color-muted); font-size: var(--fs-sm); margin-top: var(--sp-1); }
.motivo { font-style: italic; color: var(--color-muted); font-size: var(--fs-xs); margin-top: var(--sp-1); }
</style>
