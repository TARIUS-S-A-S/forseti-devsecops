<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { empresaApi, type CertificadoView } from '@/api/empresa'
import { mensajeDeError } from '@/composables/useApiError'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Password from 'primevue/password'
import FileUpload from 'primevue/fileupload'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import ConfirmDialog from 'primevue/confirmdialog'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ empresaId: string | null }>()
const confirm = useConfirm()

const certs = ref<CertificadoView[]>([])
const cargando = ref(true)
const cargandoAccion = ref<string | null>(null)  // id del cert en acción
const error = ref<string | null>(null)
const ok = ref<string | null>(null)

const dialogAbierto = ref(false)
const archivo = ref<File | null>(null)
const password = ref('')
const subiendo = ref(false)
const dialogError = ref<string | null>(null)

async function refrescar() {
  if (!props.empresaId) return
  cargando.value = true
  try {
    certs.value = await empresaApi.historialCertificados(props.empresaId)
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo cargar la lista de certificados')
  } finally {
    cargando.value = false
  }
}

onMounted(refrescar)

const certActivo = computed(() => certs.value.find(c => c.activo) ?? null)

function diasAlerta(d: number): 'success' | 'warn' | 'danger' {
  if (d < 15) return 'danger'
  if (d < 60) return 'warn'
  return 'success'
}

function fechaCorta(s: string | null): string {
  if (!s) return '—'
  return new Date(s).toLocaleDateString()
}

function abrirDialogCarga() {
  archivo.value = null
  password.value = ''
  dialogError.value = null
  dialogAbierto.value = true
}

function fileSelected(event: { files: File[] }) {
  archivo.value = event.files[0] ?? null
}

async function cargarNuevo() {
  if (!props.empresaId || !archivo.value || !password.value) {
    dialogError.value = 'Cargá el archivo .p12 y escribí su contraseña'
    return
  }
  subiendo.value = true
  dialogError.value = null
  try {
    await empresaApi.cargarCertificado(props.empresaId, archivo.value, password.value)
    dialogAbierto.value = false
    ok.value = 'Certificado cargado. Quedó como activo.'
    await refrescar()
  } catch (e) {
    dialogError.value = mensajeDeError(e, 'No se pudo cargar el certificado')
  } finally {
    subiendo.value = false
  }
}

async function activar(cert: CertificadoView) {
  if (!props.empresaId) return
  cargandoAccion.value = cert.id
  error.value = null
  try {
    await empresaApi.activarCertificado(props.empresaId, cert.id)
    ok.value = `"${cert.sujetoCn ?? cert.id}" activado.`
    await refrescar()
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo activar')
  } finally {
    cargandoAccion.value = null
  }
}

function pedirDesactivar(cert: CertificadoView) {
  confirm.require({
    message: 'Después de desactivar, la empresa NO va a poder emitir facturas hasta activar otro cert. El registro NO se borra (las facturas que firmó siguen siendo trazables).',
    header: '¿Desactivar el certificado activo?',
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, desactivar',
    acceptClass: 'p-button-danger',
    accept: async () => {
      if (!props.empresaId) return
      cargandoAccion.value = cert.id
      error.value = null
      try {
        await empresaApi.desactivarCertificado(props.empresaId)
        ok.value = `"${cert.sujetoCn ?? cert.id}" desactivado.`
        await refrescar()
      } catch (e) {
        error.value = mensajeDeError(e, 'No se pudo desactivar')
      } finally {
        cargandoAccion.value = null
      }
    },
  })
}

function pedirEliminar(cert: CertificadoView) {
  confirm.require({
    message: 'Esta acción es DEFINITIVA: borra el registro del certificado. Solo es posible si nunca firmó comprobantes. Si firmó alguno, el sistema rechaza el borrado y solo se puede desactivar.',
    header: '¿Eliminar definitivamente?',
    icon: 'pi pi-times-circle',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, eliminar',
    acceptClass: 'p-button-danger',
    accept: async () => {
      if (!props.empresaId) return
      cargandoAccion.value = cert.id
      error.value = null
      try {
        await empresaApi.eliminarCertificado(props.empresaId, cert.id)
        ok.value = `Certificado eliminado.`
        await refrescar()
      } catch (e) {
        // Si el cert firmó comprobantes, el backend devuelve 409 con mensaje claro
        error.value = mensajeDeError(e, 'No se pudo eliminar. Si firmó comprobantes, solo se puede desactivar.')
      } finally {
        cargandoAccion.value = null
      }
    },
  })
}
</script>

<template>
  <div class="firma">
    <ConfirmDialog />

    <header class="head">
      <div>
        <h2>Firma electrónica (.p12)</h2>
        <p class="sub">
          Tu .p12 se guarda <strong>cifrado con AES-256-GCM</strong>. La contraseña no viaja
          al frontend nunca más; solo el motor de firma la descifra al emitir cada comprobante.
        </p>
      </div>
      <Button label="Cargar otro certificado" icon="pi pi-plus" @click="abrirDialogCarga" />
    </header>

    <Message v-if="ok" severity="success" :closable="true" @close="ok = null">{{ ok }}</Message>
    <Message v-if="error" severity="error" :closable="true" @close="error = null">{{ error }}</Message>

    <div v-if="cargando" class="loading">Cargando…</div>
    <div v-else-if="certs.length === 0" class="vacio">
      <i class="pi pi-key" />
      <p>Esta empresa no tiene certificados cargados.</p>
      <Button label="Cargar el primero" icon="pi pi-upload" @click="abrirDialogCarga" />
    </div>
    <div v-else class="table-scroll">
    <DataTable :value="certs" data-key="id" class="tabla">
      <Column field="sujetoCn" header="Titular">
        <template #body="{ data }">
          <div class="celda-sujeto">
            <strong>{{ data.sujetoCn ?? '(sin CN)' }}</strong>
            <span class="serie mono">SN: {{ data.numeroSerie ?? '—' }}</span>
          </div>
        </template>
      </Column>
      <Column field="emisorCn" header="Emisor">
        <template #body="{ data }">{{ data.emisorCn ?? '—' }}</template>
      </Column>
      <Column header="Vigencia">
        <template #body="{ data }">
          <div class="celda-vig">
            <span>hasta {{ fechaCorta(data.vigenteHasta) }}</span>
            <Tag :value="`${data.diasParaCaducar}d`"
                 :severity="diasAlerta(data.diasParaCaducar)" />
          </div>
        </template>
      </Column>
      <Column header="Estado">
        <template #body="{ data }">
          <Tag v-if="data.activo" value="ACTIVO" severity="success" />
          <Tag v-else value="Inactivo" severity="secondary" />
        </template>
      </Column>
      <Column header="Acciones" class="acciones-col">
        <template #body="{ data }">
          <div class="acciones">
            <Button v-if="!data.activo" label="Activar" icon="pi pi-check" size="small"
                    :loading="cargandoAccion === data.id"
                    @click="activar(data)" />
            <Button v-if="data.activo" label="Desactivar" icon="pi pi-pause" size="small"
                    severity="warn" outlined
                    :loading="cargandoAccion === data.id"
                    @click="pedirDesactivar(data)" />
            <Button label="Eliminar" icon="pi pi-trash" size="small"
                    severity="danger" text
                    :loading="cargandoAccion === data.id"
                    @click="pedirEliminar(data)" />
          </div>
        </template>
      </Column>
    </DataTable>
    </div>

    <div v-if="!certActivo && certs.length > 0" class="alerta-sin-activo">
      <i class="pi pi-exclamation-triangle" />
      <span>No hay ningún certificado activo. La empresa <strong>no puede emitir facturas</strong>
        hasta activar uno de los certs cargados o subir uno nuevo.</span>
    </div>

    <!-- Dialog de carga -->
    <Dialog v-model:visible="dialogAbierto" modal :draggable="false"
            header="Cargar nuevo certificado" :style="{ width: '500px' }"
            :breakpoints="{ '768px': '92vw' }">
      <div class="dialog-form">
        <p class="sub">
          Subí el archivo .p12 y la contraseña con la que te entregaron la firma.
          Si ya hay un cert activo, este nuevo lo reemplaza automáticamente.
        </p>
        <FileUpload mode="basic" accept=".p12,application/x-pkcs12"
                    :max-file-size="262144"
                    choose-label="Seleccionar .p12"
                    @select="fileSelected" />
        <div v-if="archivo" class="archivo-info">
          <i class="pi pi-file" />
          <span>{{ archivo.name }}</span>
          <span class="size">({{ Math.round(archivo.size / 1024) }} KB)</span>
        </div>
        <div class="field">
          <label for="pwd-nuevo">Contraseña del .p12</label>
          <Password id="pwd-nuevo" v-model="password" toggle-mask :feedback="false"
                    input-class="full-width" />
        </div>
        <Message v-if="dialogError" severity="error" :closable="false">{{ dialogError }}</Message>
      </div>
      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogAbierto = false" />
        <Button :label="subiendo ? 'Cargando…' : 'Cargar'" :loading="subiendo"
                :disabled="!archivo || !password" @click="cargarNuevo" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.firma { display: flex; flex-direction: column; gap: var(--sp-4); }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--sp-4); flex-wrap: wrap; }
.head h2 { font-family: var(--font-display); margin: 0 0 var(--sp-2); }
@media (max-width: 768px) {
  .head > div { flex: 1 1 100%; }
  .acciones-col { width: auto !important; }
}
.sub { color: var(--color-muted); margin: 0; max-width: 600px; }
.loading { color: var(--color-muted); padding: var(--sp-4); }
.vacio { text-align: center; padding: var(--sp-6); color: var(--color-muted); display: flex; flex-direction: column; align-items: center; gap: var(--sp-3); border: 1px dashed var(--color-border); border-radius: var(--radius-md); }
.vacio i { font-size: var(--fs-2xl); }
.celda-sujeto { display: flex; flex-direction: column; }
.celda-sujeto .serie { font-size: var(--fs-xs); color: var(--color-muted); }
.celda-vig { display: flex; align-items: center; gap: var(--sp-2); }
.acciones-col { width: 280px; }
.acciones { display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.alerta-sin-activo { display: flex; align-items: center; gap: var(--sp-3); padding: var(--sp-3) var(--sp-4); background: var(--color-warning-bg); color: var(--color-warning); border-radius: var(--radius-md); border: 1px solid var(--color-warning); }
.mono { font-family: var(--font-mono); }
.dialog-form { display: flex; flex-direction: column; gap: var(--sp-3); }
.archivo-info { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2); background: var(--color-marca-bg); border-radius: var(--radius-sm); }
.archivo-info .size { color: var(--color-muted); margin-left: auto; font-size: var(--fs-sm); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; }
.full-width { width: 100%; }
</style>
