<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { empresaApi, type Empresa } from '@/api/empresa'
import { mensajeDeError } from '@/composables/useApiError'
import { useAuthStore } from '@/stores/auth'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ConfirmDialog from 'primevue/confirmdialog'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ empresa: Empresa | null }>()
const confirm = useConfirm()
const auth = useAuthStore()
const router = useRouter()

const archivando = ref(false)
const reactivando = ref(false)
const error = ref<string | null>(null)
const ok = ref<string | null>(null)

function pedirArchivar() {
  if (!props.empresa) return
  confirm.require({
    message:
      'Esta acción ARCHIVA la empresa. Sus facturas, comprobantes y datos QUEDAN INTACTOS ' +
      '(la ley exige conservarlos 7 años — no se pueden borrar). Pero la empresa desaparece ' +
      'del selector y NO podés emitir nuevos comprobantes con ella. Podés reactivarla más ' +
      'tarde como DUEÑO de la empresa. ¿Confirmás?',
    header: '⚠ Archivar empresa: ' + (props.empresa.razonSocial),
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, archivar',
    acceptClass: 'p-button-danger',
    accept: async () => {
      if (!props.empresa) return
      archivando.value = true
      error.value = null
      try {
        await empresaApi.archivarEmpresa(props.empresa.id)
        ok.value = 'Empresa archivada. Vas al selector de empresas.'
        await auth.fetchUser()
        setTimeout(() => router.push({ name: 'empresa-selector' }), 800)
      } catch (e) {
        error.value = mensajeDeError(e, 'No se pudo archivar la empresa')
      } finally {
        archivando.value = false
      }
    },
  })
}

async function reactivar() {
  if (!props.empresa) return
  reactivando.value = true
  error.value = null
  try {
    await empresaApi.reactivarEmpresa(props.empresa.id)
    ok.value = 'Empresa reactivada.'
    await auth.fetchUser()
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo reactivar la empresa')
  } finally {
    reactivando.value = false
  }
}
</script>

<template>
  <div class="zona-peligro">
    <ConfirmDialog />
    <h3>Zona de riesgo</h3>
    <p class="sub">Acciones irreversibles o que afectan la empresa completa. Solo el DUEÑO.</p>

    <Message v-if="ok" severity="success" :closable="true" @close="ok = null">{{ ok }}</Message>
    <Message v-if="error" severity="error" :closable="true" @close="error = null">{{ error }}</Message>

    <div v-if="empresa" class="acciones">
      <div class="accion">
        <div>
          <strong>{{ empresa.activa ? 'Archivar empresa' : 'Empresa archivada' }}</strong>
          <p class="ayuda">
            {{ empresa.activa
              ? 'Soft-delete. Los datos y facturas se preservan (LOPDP + ley fiscal 7 años) pero la empresa desaparece del selector. Se puede reactivar.'
              : 'Esta empresa está archivada. Reactivá para volver a verla en el selector y poder emitir comprobantes.' }}
          </p>
        </div>
        <Button v-if="empresa.activa" label="Archivar" icon="pi pi-trash"
                severity="danger" outlined :loading="archivando"
                @click="pedirArchivar" />
        <Button v-else label="Reactivar" icon="pi pi-refresh"
                :loading="reactivando" @click="reactivar" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.zona-peligro { background: var(--color-surface); border: 1px solid var(--color-danger); border-left-width: 4px; border-radius: var(--radius-md); padding: var(--sp-4); display: flex; flex-direction: column; gap: var(--sp-3); }
.zona-peligro h3 { margin: 0; color: var(--color-danger); font-family: var(--font-display); }
.sub { color: var(--color-muted); margin: 0; font-size: var(--fs-sm); }
.acciones { display: flex; flex-direction: column; gap: var(--sp-3); }
.accion { display: flex; justify-content: space-between; align-items: center; gap: var(--sp-4); padding: var(--sp-3); background: var(--color-danger-bg); border-radius: var(--radius-md); flex-wrap: wrap; }
.ayuda { color: var(--color-muted); margin: var(--sp-1) 0 0; font-size: var(--fs-sm); }
</style>
