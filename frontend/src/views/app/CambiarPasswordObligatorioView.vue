<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Message from 'primevue/message'

const router = useRouter()
const auth = useAuthStore()

const form = reactive({
  passwordActual: '',
  passwordNueva: '',
  confirmacion: '',
})
const procesando = ref(false)
const error = ref<string | null>(null)

async function submit() {
  error.value = null
  if (form.passwordNueva !== form.confirmacion) {
    error.value = 'Las contraseñas nuevas no coinciden'
    return
  }
  if (form.passwordNueva.length < 8) {
    error.value = 'La contraseña nueva debe tener al menos 8 caracteres'
    return
  }
  procesando.value = true
  try {
    await authApi.cambiarPassword(form.passwordActual, form.passwordNueva)
    await auth.refrescar()
    router.push({ name: 'dashboard' })
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'No se pudo cambiar la contraseña'
  } finally {
    procesando.value = false
  }
}
</script>

<template>
  <div class="cambio">
    <header class="page-head">
      <h1>Cambiá tu contraseña</h1>
      <p class="subtitulo">
        Iniciaste sesión con una contraseña temporal. Antes de seguir, definí una nueva
        que solo vos sepas.
      </p>
    </header>

    <Message v-if="error" severity="error">{{ error }}</Message>

    <form class="form" @submit.prevent="submit">
      <div class="field">
        <label for="actual">Contraseña actual (la temporal)</label>
        <Password id="actual" v-model="form.passwordActual" toggle-mask :feedback="false"
                  input-class="full-width" required />
      </div>
      <div class="field">
        <label for="nueva">Contraseña nueva</label>
        <Password id="nueva" v-model="form.passwordNueva" toggle-mask
                  input-class="full-width" required />
        <small>Mínimo 8 caracteres.</small>
      </div>
      <div class="field">
        <label for="conf">Repetí la contraseña nueva</label>
        <Password id="conf" v-model="form.confirmacion" toggle-mask :feedback="false"
                  input-class="full-width" required />
      </div>
      <div class="actions">
        <Button type="submit" :loading="procesando" label="Guardar y entrar" icon="pi pi-check" />
      </div>
    </form>
  </div>
</template>

<style scoped>
.cambio { max-width: 480px; margin: 0 auto; }
.page-head { margin-bottom: var(--sp-6); }
.page-head h1 { font-family: var(--font-display); }
.subtitulo { color: var(--color-muted); }
.form { display: flex; flex-direction: column; gap: var(--sp-4); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-weight: 500; font-size: var(--fs-sm); }
.field small { color: var(--color-muted); font-size: var(--fs-xs); }
.full-width { width: 100%; }
.actions { display: flex; justify-content: flex-end; margin-top: var(--sp-2); }
</style>
