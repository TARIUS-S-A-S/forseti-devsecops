<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { miembrosApi, type Miembro, type InvitacionView, type Rol } from '@/api/miembros'
import { mensajeDeError } from '@/composables/useApiError'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import Tag from 'primevue/tag'
import ConfirmDialog from 'primevue/confirmdialog'
import { useConfirm } from 'primevue/useconfirm'

const auth = useAuthStore()
const confirm = useConfirm()
const empresaId = computed(() => auth.empresaActivaId)
const miPropioId = computed(() => auth.user?.id)
const miRol = computed(() => auth.empresaActiva?.rol)
const soyGestor = computed(() => miRol.value === 'DUENO' || miRol.value === 'ADMIN')

const miembros = ref<Miembro[]>([])
const invitaciones = ref<InvitacionView[]>([])
const cargando = ref(true)
const error = ref<string | null>(null)

const rolesDisponibles = [
  { label: 'Dueño', value: 'DUENO' },
  { label: 'Administrador', value: 'ADMIN' },
  { label: 'Contadora', value: 'CONTADORA' },
  { label: 'Empleado', value: 'EMPLEADO' },
]

const dialogTipo = ref<'EMAIL' | 'USERNAME' | null>(null)
const formNuevo = reactive({
  nombre: '',
  email: '',
  username: '',
  password: '',
  rol: 'EMPLEADO' as Rol,
})
const procesando = ref(false)
const credencialesGeneradas = ref<{ username?: string; password: string; mensaje: string } | null>(null)

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
    miembros.value = await miembrosApi.listarMiembros(empresaId.value)
    if (soyGestor.value) {
      invitaciones.value = await miembrosApi.listarInvitaciones(empresaId.value)
    }
  } catch {
    error.value = 'No se pudo cargar la información'
  } finally {
    cargando.value = false
  }
}

function abrirDialog(tipo: 'EMAIL' | 'USERNAME') {
  dialogTipo.value = tipo
  formNuevo.nombre = ''
  formNuevo.email = ''
  formNuevo.username = ''
  formNuevo.password = ''
  formNuevo.rol = 'EMPLEADO'
  credencialesGeneradas.value = null
  error.value = null
}

async function submitNuevo() {
  if (!empresaId.value) return
  procesando.value = true
  error.value = null
  try {
    if (dialogTipo.value === 'EMAIL') {
      const r = await miembrosApi.invitarPorEmail(empresaId.value, formNuevo.email, formNuevo.nombre, formNuevo.rol)
      credencialesGeneradas.value = { password: '', mensaje: r.mensaje }
      await recargar()
    } else if (dialogTipo.value === 'USERNAME') {
      const r = await miembrosApi.crearConUsername(empresaId.value, formNuevo.username, formNuevo.nombre, formNuevo.rol, formNuevo.password)
      credencialesGeneradas.value = { username: r.username, password: r.passwordTemporal, mensaje: r.mensaje }
      await recargar()
    }
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo completar la operación')
  } finally {
    procesando.value = false
  }
}

function cerrarDialog() {
  dialogTipo.value = null
  credencialesGeneradas.value = null
}

async function cambiarRol(m: Miembro, nuevoRol: Rol) {
  if (!empresaId.value) return
  if (nuevoRol === m.rol) return
  try {
    await miembrosApi.cambiarRol(empresaId.value, m.usuarioId, nuevoRol)
    await recargar()
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo cambiar el rol')
  }
}

function quitar(m: Miembro) {
  if (!empresaId.value) return
  confirm.require({
    header: `Quitar a ${m.nombre}`,
    message: `Esto remueve a ${m.nombre} de esta empresa. Si vuelve a necesitar acceso vas a tener que invitarlo de nuevo.`,
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, quitar',
    acceptClass: 'p-button-danger',
    accept: async () => {
      if (!empresaId.value) return
      try {
        await miembrosApi.quitar(empresaId.value, m.usuarioId)
        await recargar()
      } catch (e) {
        error.value = mensajeDeError(e, 'No se pudo quitar el miembro')
      }
    },
  })
}

function resetPwd(m: Miembro) {
  if (!empresaId.value) return
  confirm.require({
    header: `Resetear contraseña de ${m.nombre}`,
    message: 'Vas a generar una contraseña temporal nueva. La anterior dejará de funcionar y vas a tener que entregarle la nueva al usuario.',
    icon: 'pi pi-refresh',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, resetear',
    accept: async () => {
      if (!empresaId.value) return
      try {
        const r = await miembrosApi.resetPassword(empresaId.value, m.usuarioId)
        credencialesGeneradas.value = { username: m.username ?? undefined, password: r.passwordTemporal, mensaje: r.mensaje }
        dialogTipo.value = 'USERNAME' // reusa el dialog para mostrar credenciales
      } catch (e) {
        error.value = mensajeDeError(e, 'No se pudo resetear la contraseña')
      }
    },
  })
}

function cancelarInvitacion(i: InvitacionView) {
  if (!empresaId.value) return
  confirm.require({
    header: 'Cancelar invitación',
    message: `Cancelar la invitación enviada a ${i.email}. El link expira y deja de funcionar.`,
    icon: 'pi pi-times',
    rejectLabel: 'Volver',
    acceptLabel: 'Sí, cancelar',
    acceptClass: 'p-button-danger',
    accept: async () => {
      if (!empresaId.value) return
      try {
        await miembrosApi.cancelarInvitacion(empresaId.value, i.id)
        await recargar()
      } catch (e) {
        error.value = mensajeDeError(e, 'No se pudo cancelar la invitación')
      }
    },
  })
}

function copiar(texto: string) {
  navigator.clipboard?.writeText(texto)
}
</script>

<template>
  <div class="miembros">
    <ConfirmDialog />
    <header class="page-head">
      <h1>Miembros</h1>
      <p class="page-sub">
        Quién accede a esta empresa y qué puede hacer. Cada empresa lleva sus propios miembros
        con sus propios roles — el aislamiento entre empresas está garantizado por RLS de DB.
      </p>
    </header>

    <Message v-if="error" severity="error">{{ error }}</Message>

    <div v-if="cargando" class="loading">Cargando…</div>

    <template v-else>
      <Message v-if="!soyGestor" severity="info" :closable="false" class="hint">
        Sos {{ miRol }} en esta empresa. Solo el DUEÑO o un ADMIN pueden gestionar miembros.
      </Message>

      <div v-if="soyGestor" class="acciones-globales">
        <Button label="Invitar por email" icon="pi pi-envelope" @click="abrirDialog('EMAIL')" />
        <Button label="Crear con nombre de usuario" icon="pi pi-user-plus"
                severity="secondary" outlined @click="abrirDialog('USERNAME')" />
      </div>

      <section class="seccion">
        <h2>Miembros activos ({{ miembros.length }})</h2>
        <ul class="lista">
          <li v-for="m in miembros" :key="m.usuarioId" class="item">
            <div class="info">
              <strong>{{ m.nombre }}</strong>
              <span class="identificador">
                <i :class="['pi', m.email ? 'pi-envelope' : 'pi-user']" />
                {{ m.email ?? m.username }}
              </span>
              <span v-if="m.debeCambiarPassword" class="warn-pwd">
                <i class="pi pi-exclamation-triangle" /> Debe cambiar contraseña
              </span>
              <span v-if="m.usuarioId === miPropioId" class="tag-self">vos</span>
            </div>
            <div class="acciones">
              <Select v-if="soyGestor && m.usuarioId !== miPropioId" :model-value="m.rol"
                      :options="rolesDisponibles" option-label="label" option-value="value"
                      @update:model-value="(v) => cambiarRol(m, v as Rol)" />
              <Tag v-else :value="m.rol" />
              <Button v-if="soyGestor && !m.email && m.usuarioId !== miPropioId"
                      icon="pi pi-refresh" title="Resetear contraseña"
                      severity="warn" text rounded @click="resetPwd(m)" />
              <Button v-if="soyGestor && m.usuarioId !== miPropioId"
                      icon="pi pi-trash" title="Quitar"
                      severity="danger" text rounded @click="quitar(m)" />
            </div>
          </li>
        </ul>
      </section>

      <section v-if="soyGestor && invitaciones.length > 0" class="seccion">
        <h2>Invitaciones pendientes ({{ invitaciones.length }})</h2>
        <ul class="lista">
          <li v-for="i in invitaciones" :key="i.id" class="item">
            <div class="info">
              <strong>{{ i.nombreInvitado ?? i.email }}</strong>
              <span class="identificador">
                <i class="pi pi-envelope" /> {{ i.email }}
              </span>
              <span class="expira">Expira {{ new Date(i.expiraAt).toLocaleDateString() }}</span>
            </div>
            <div class="acciones">
              <Tag :value="i.rol" />
              <Button icon="pi pi-times" title="Cancelar invitación"
                      severity="danger" text rounded @click="cancelarInvitacion(i)" />
            </div>
          </li>
        </ul>
      </section>
    </template>

    <!-- Dialog crear/invitar/credenciales -->
    <Dialog v-model:visible="dialogTipo as unknown as boolean" :modal="true" :closable="!procesando"
            :header="dialogTipo === 'EMAIL' ? 'Invitar por email' : (credencialesGeneradas?.username ? 'Credenciales del usuario' : 'Crear con nombre de usuario')"
            @hide="cerrarDialog" :style="{ width: '480px' }"
            :breakpoints="{ '768px': '92vw' }">
      <!-- Pantalla 2: credenciales generadas -->
      <div v-if="credencialesGeneradas" class="credenciales">
        <Message severity="success" :closable="false">{{ credencialesGeneradas.mensaje }}</Message>
        <div v-if="credencialesGeneradas.username" class="cred">
          <label>Usuario</label>
          <div class="cred-valor">
            <code>{{ credencialesGeneradas.username }}</code>
            <Button icon="pi pi-copy" text rounded @click="copiar(credencialesGeneradas!.username!)" />
          </div>
        </div>
        <div v-if="credencialesGeneradas.password" class="cred">
          <label>Contraseña temporal</label>
          <div class="cred-valor">
            <code>{{ credencialesGeneradas.password }}</code>
            <Button icon="pi pi-copy" text rounded @click="copiar(credencialesGeneradas!.password)" />
          </div>
        </div>
        <Message v-if="credencialesGeneradas.password" severity="warn" :closable="false">
          <strong>Anotala ahora.</strong> Esta es la única vez que la podés ver. El usuario va a tener que cambiarla al primer login.
        </Message>
        <div class="botones">
          <Button label="Listo" @click="cerrarDialog" />
        </div>
      </div>

      <!-- Pantalla 1: form -->
      <form v-else class="form" @submit.prevent="submitNuevo">
        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
        <div class="field">
          <label for="nombre">Nombre completo</label>
          <InputText id="nombre" v-model="formNuevo.nombre" required />
        </div>

        <div v-if="dialogTipo === 'EMAIL'" class="field">
          <label for="email">Email</label>
          <InputText id="email" v-model="formNuevo.email" type="email" required
                     placeholder="contadora@ejemplo.com" />
          <small>Le mandamos un correo con un link de invitación. Expira en 7 días.</small>
        </div>

        <template v-if="dialogTipo === 'USERNAME'">
          <div class="field">
            <label for="username">Nombre de usuario</label>
            <InputText id="username" v-model="formNuevo.username" required
                       placeholder="juan_perez"
                       pattern="[a-zA-Z0-9._-]{3,40}" />
            <small>3 a 40 caracteres. Minúsculas, números, punto, guión y guión bajo.</small>
          </div>
          <div class="field">
            <label for="pwd">Contraseña temporal (opcional)</label>
            <InputText id="pwd" v-model="formNuevo.password" type="text"
                       placeholder="Vacío = la generamos nosotros" />
            <small>Si la dejás vacía, Forseti genera una segura. El usuario va a tener que cambiarla al primer login.</small>
          </div>
        </template>

        <div class="field">
          <label for="rol">Rol</label>
          <Select id="rol" v-model="formNuevo.rol" :options="rolesDisponibles"
                  option-label="label" option-value="value" />
        </div>

        <div class="botones">
          <Button label="Cancelar" severity="secondary" text @click="cerrarDialog" />
          <Button type="submit" :label="dialogTipo === 'EMAIL' ? 'Enviar invitación' : 'Crear usuario'"
                  :loading="procesando" icon="pi pi-check" />
        </div>
      </form>
    </Dialog>
  </div>
</template>

<style scoped>
.miembros { max-width: 900px; margin: 0 auto; display: flex; flex-direction: column; gap: var(--sp-4); }
.loading { text-align: center; padding: var(--sp-8); color: var(--color-muted); }
.hint { margin-bottom: var(--sp-4); }
.acciones-globales { display: flex; gap: var(--sp-3); margin-bottom: var(--sp-6); }
.seccion { margin-bottom: var(--sp-6); }
.seccion h2 { font-family: var(--font-display); font-size: var(--fs-lg); margin-bottom: var(--sp-3); padding-bottom: var(--sp-2); border-bottom: 2px solid var(--color-marca); }
.lista { list-style: none; padding: 0; display: flex; flex-direction: column; gap: var(--sp-2); }
.item { display: flex; justify-content: space-between; align-items: center; gap: var(--sp-4); padding: var(--sp-4); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); flex-wrap: wrap; }
.info { display: flex; flex-direction: column; gap: var(--sp-1); flex: 1; }
.identificador { color: var(--color-muted); font-size: var(--fs-sm); display: flex; align-items: center; gap: var(--sp-1); }
.warn-pwd { color: var(--color-warning); font-size: var(--fs-xs); display: flex; align-items: center; gap: var(--sp-1); }
.tag-self { display: inline-block; font-size: var(--fs-xs); background: var(--color-marca-bg); color: var(--color-marca-deep); padding: 2px 8px; border-radius: var(--radius-sm); font-weight: 600; }
.expira { color: var(--color-muted); font-size: var(--fs-xs); }
.acciones { display: flex; align-items: center; gap: var(--sp-2); }
.form { display: flex; flex-direction: column; gap: var(--sp-4); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; font-size: var(--fs-sm); }
.field small { color: var(--color-muted); font-size: var(--fs-xs); }
.botones { display: flex; justify-content: flex-end; gap: var(--sp-2); margin-top: var(--sp-2); }
.credenciales { display: flex; flex-direction: column; gap: var(--sp-4); }
.cred { display: flex; flex-direction: column; gap: var(--sp-2); }
.cred-valor { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-3); background: var(--color-bg); border-radius: var(--radius-md); }
.cred-valor code { flex: 1; font-family: var(--font-mono); font-size: var(--fs-md); }
</style>
