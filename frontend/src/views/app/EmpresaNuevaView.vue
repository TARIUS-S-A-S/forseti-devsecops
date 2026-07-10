<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { empresaApi, type CrearEmpresaRequest } from '@/api/empresa'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Checkbox from 'primevue/checkbox'
import Button from 'primevue/button'
import Message from 'primevue/message'

const router = useRouter()
const auth = useAuthStore()

const form = reactive<CrearEmpresaRequest>({
  ruc: '',
  razonSocial: '',
  nombreComercial: '',
  tipoContribuyente: 'SAS',
  regimenTributario: 'RIMPE_EMPRENDEDOR',
  periodicidadIva: 'SEMESTRAL',
  obligadoContabilidad: false,
  agenteRetencion: false,
  direccion: '',
  ciudad: 'Quito',
  provincia: 'Pichincha',
  telefono: '',
  email: '',
})

const tiposContribuyente = [
  { label: 'Persona natural', value: 'PN' },
  { label: 'S.A. (Sociedad Anónima)', value: 'SA' },
  { label: 'S.A.S. (Sociedad por Acciones Simplificada)', value: 'SAS' },
  { label: 'Cía. Ltda.', value: 'LTDA' },
  { label: 'Empresa Pública', value: 'EP' },
  { label: 'Otro', value: 'OTRO' },
]

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

const loading = ref(false)
const error = ref<string | null>(null)

async function submit() {
  error.value = null
  loading.value = true
  try {
    const empresa = await empresaApi.crear(form)
    await auth.refrescar()
    await auth.cambiarEmpresaActiva(empresa.id)
    router.push({ name: 'cert-upload', params: { empresaId: empresa.id } })
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'Error inesperado al crear la empresa'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="empresa-nueva">
    <header class="page-head">
      <h1>Nueva empresa</h1>
      <p class="subtitulo">
        Configurá los datos de tu empresa. Podés cambiar régimen / periodicidad después
        sin perder historial.
      </p>
    </header>

    <Message v-if="error" severity="error" :closable="false" class="error">
      {{ error }}
    </Message>

    <form class="form" @submit.prevent="submit">
      <fieldset>
        <legend>Identificación</legend>

        <div class="field">
          <label for="ruc">RUC</label>
          <InputText id="ruc" v-model="form.ruc" placeholder="13 dígitos" maxlength="13" required />
          <small>13 dígitos. Lo valida el SRI; verificá que termine en 001.</small>
        </div>

        <div class="field">
          <label for="razon">Razón social</label>
          <InputText id="razon" v-model="form.razonSocial" required />
        </div>

        <div class="field">
          <label for="comercial">Nombre comercial (opcional)</label>
          <InputText id="comercial" v-model="form.nombreComercial" />
        </div>

        <div class="field">
          <label for="tipo">Tipo de contribuyente</label>
          <Select id="tipo" v-model="form.tipoContribuyente" :options="tiposContribuyente"
                  option-label="label" option-value="value" />
        </div>
      </fieldset>

      <fieldset>
        <legend>Régimen tributario</legend>

        <div class="field">
          <label for="regimen">Régimen</label>
          <Select id="regimen" v-model="form.regimenTributario" :options="regimenes"
                  option-label="label" option-value="value" />
        </div>

        <div class="field">
          <label for="periodicidad">Periodicidad de IVA</label>
          <Select id="periodicidad" v-model="form.periodicidadIva" :options="periodicidades"
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
      </fieldset>

      <fieldset>
        <legend>Contacto</legend>

        <div class="field-row">
          <div class="field">
            <label for="dir">Dirección</label>
            <InputText id="dir" v-model="form.direccion" />
          </div>
          <div class="field">
            <label for="ciudad">Ciudad</label>
            <InputText id="ciudad" v-model="form.ciudad" />
          </div>
          <div class="field">
            <label for="prov">Provincia</label>
            <InputText id="prov" v-model="form.provincia" />
          </div>
        </div>

        <div class="field-row">
          <div class="field">
            <label for="tel">Teléfono</label>
            <InputText id="tel" v-model="form.telefono" />
          </div>
          <div class="field">
            <label for="em">Email</label>
            <InputText id="em" v-model="form.email" type="email" />
          </div>
        </div>
      </fieldset>

      <div class="actions">
        <Button type="button" label="Cancelar" severity="secondary" text
                @click="router.back()" />
        <Button type="submit" label="Crear empresa" :loading="loading"
                icon="pi pi-check" />
      </div>
    </form>
  </div>
</template>

<style scoped>
.empresa-nueva { max-width: 760px; margin: 0 auto; }
.page-head { margin-bottom: var(--sp-6); }
.page-head h1 { font-family: var(--font-display); margin-bottom: var(--sp-2); }
.subtitulo { color: var(--color-muted); }
.error { margin-bottom: var(--sp-4); }
.form { display: flex; flex-direction: column; gap: var(--sp-6); }
fieldset { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--sp-5); display: flex; flex-direction: column; gap: var(--sp-4); }
legend { padding: 0 var(--sp-2); font-weight: 600; color: var(--color-ink); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); flex: 1; }
.field label { font-weight: 500; color: var(--color-ink); font-size: var(--fs-sm); }
.field small { color: var(--color-muted); font-size: var(--fs-xs); }
.field-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--sp-4); }
.checkbox-row { display: flex; align-items: center; gap: var(--sp-2); }
.actions { display: flex; justify-content: flex-end; gap: var(--sp-3); margin-top: var(--sp-4); }
</style>
