<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { miembrosApi, type InvitacionPublicView } from '@/api/miembros'
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'
import Button from 'primevue/button'
import Message from 'primevue/message'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const token = String(route.params.token)
const invitacion = ref<InvitacionPublicView | null>(null)
const cargando = ref(true)
const error = ref<string | null>(null)
const procesando = ref(false)
const aceptada = ref(false)

const rolLabel: Record<string, string> = {
  DUENO: 'Dueño',
  ADMIN: 'Administrador',
  CONTADORA: 'Contadora',
  EMPLEADO: 'Empleado',
}

onMounted(async () => {
  try {
    invitacion.value = await miembrosApi.verInvitacionPublica(token)
  } catch {
    error.value = 'No encontramos esta invitación. El link puede ser inválido o haber expirado.'
  } finally {
    cargando.value = false
  }
})

async function aceptar() {
  if (!auth.isAuthenticated) {
    // Guardar el redirect para volver acá después del login
    router.push({ name: 'login', query: { redirect: `/invitacion/${token}` } })
    return
  }
  // Si está logueado pero con un email distinto del invitado, avisar
  if (
    auth.user?.email &&
    invitacion.value?.email &&
    auth.user.email.toLowerCase() !== invitacion.value.email.toLowerCase()
  ) {
    error.value = `Esta invitación es para ${invitacion.value.email}. Estás logueado como ${auth.user.email}. Cerrá sesión e iniciá con la cuenta correcta.`
    return
  }
  procesando.value = true
  try {
    const r = await miembrosApi.aceptarInvitacion(token)
    await auth.refrescar()
    await auth.cambiarEmpresaActiva(r.empresaId)
    aceptada.value = true
    setTimeout(() => router.push({ name: 'dashboard' }), 1500)
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'No se pudo aceptar la invitación.'
  } finally {
    procesando.value = false
  }
}

function irARegistro() {
  router.push({
    name: 'registro',
    query: { email: invitacion.value?.email, invitacion: token },
  })
}
</script>

<template>
  <div class="page">
    <div class="card">
      <RouterLink to="/" class="brand">
        <ForsetiLogo variant="ink" :size="40" />
        <span class="forseti-wordmark brand-text">Forseti</span>
      </RouterLink>

      <div v-if="cargando" class="estado">Cargando invitación…</div>

      <Message v-else-if="error && !invitacion" severity="error" :closable="false">{{ error }}</Message>

      <template v-else-if="invitacion">
        <h1>Te invitaron a unirte</h1>
        <div class="empresa">
          <strong>{{ invitacion.nombreEmpresa }}</strong>
          <span class="rol">como {{ rolLabel[invitacion.rol] ?? invitacion.rol }}</span>
        </div>
        <p class="email">Invitación enviada a <strong>{{ invitacion.email }}</strong></p>

        <Message v-if="invitacion.expirada" severity="warn" :closable="false">
          La invitación expiró el {{ new Date(invitacion.expiraAt).toLocaleString() }}.
          Pedile al dueño de la empresa que te invite de nuevo.
        </Message>

        <Message v-else-if="invitacion.yaAceptada" severity="info" :closable="false">
          Esta invitación ya fue aceptada.
        </Message>

        <Message v-else-if="invitacion.cancelada" severity="warn" :closable="false">
          Esta invitación fue cancelada.
        </Message>

        <Message v-else-if="aceptada" severity="success" :closable="false">
          ¡Listo! Estás dentro de {{ invitacion.nombreEmpresa }}. Redirigiendo…
        </Message>

        <Message v-else-if="error" severity="error" :closable="false">{{ error }}</Message>

        <div v-if="!invitacion.noDisponible && !aceptada" class="acciones">
          <template v-if="auth.isAuthenticated">
            <Button label="Aceptar invitación" icon="pi pi-check"
                    :loading="procesando" @click="aceptar" />
          </template>
          <template v-else>
            <p class="hint">Para aceptar, iniciá sesión o creá tu cuenta con el email <strong>{{ invitacion.email }}</strong>.</p>
            <div class="botones">
              <Button label="Iniciar sesión" icon="pi pi-sign-in"
                      @click="router.push({ name: 'login', query: { redirect: `/invitacion/${token}` } })" />
              <Button label="Crear cuenta" icon="pi pi-user-plus" severity="secondary" outlined
                      @click="irARegistro" />
            </div>
          </template>
        </div>
      </template>

      <div class="endorsement">
        <EndorsementTarius />
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--color-bg); padding: var(--sp-6); }
.card { background: var(--color-surface); border-radius: var(--radius-lg); padding: var(--sp-8); max-width: 520px; width: 100%; box-shadow: 0 4px 24px rgba(0,0,0,0.06); display: flex; flex-direction: column; gap: var(--sp-4); }
.brand { display: inline-flex; align-items: center; gap: var(--sp-2); color: var(--color-ink); margin-bottom: var(--sp-4); }
.brand-text { font-size: var(--fs-xl); }
.estado { text-align: center; color: var(--color-muted); padding: var(--sp-4) 0; }
h1 { font-family: var(--font-display); font-size: var(--fs-2xl); margin: 0; }
.empresa { display: flex; flex-direction: column; gap: var(--sp-1); padding: var(--sp-4); background: var(--color-marca-bg); border-radius: var(--radius-md); }
.empresa strong { font-size: var(--fs-xl); color: var(--color-ink); }
.empresa .rol { color: var(--color-marca-deep); font-weight: 500; }
.email { color: var(--color-muted); font-size: var(--fs-sm); margin: 0; }
.acciones { display: flex; flex-direction: column; gap: var(--sp-3); margin-top: var(--sp-2); }
.hint { color: var(--color-muted); font-size: var(--fs-sm); margin: 0; }
.botones { display: flex; gap: var(--sp-2); }
.endorsement { text-align: center; margin-top: var(--sp-4); }
</style>
