<script setup lang="ts">
import { ref } from 'vue'
import axios from 'axios'
import { authApi } from '@/api/auth'
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Message from 'primevue/message'

const email = ref('')
const nombre = ref('')
const username = ref('')
const password = ref('')
const passwordConfirm = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const exito = ref(false)
const fieldErrors = ref<Record<string, string>>({})

function validate(): boolean {
  const errs: Record<string, string> = {}
  if (nombre.value.trim().length < 2) {
    errs.nombre = 'Ingresá tu nombre (mín. 2 caracteres).'
  }
  const e = email.value.trim()
  if (!e) {
    errs.email = 'El correo es obligatorio.'
  } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e)) {
    errs.email = 'Ingresá un correo válido.'
  }
  const u = username.value.trim()
  if (!u) {
    errs.username = 'El nombre de usuario es obligatorio.'
  } else if (!/^[a-z0-9._-]{3,40}$/.test(u)) {
    errs.username = 'Usá 3-40 caracteres: minúsculas, números, punto, guión o guión bajo.'
  }
  if (password.value.length < 8) {
    errs.password = 'La contraseña debe tener al menos 8 caracteres.'
  }
  if (!passwordConfirm.value) {
    errs.passwordConfirm = 'Repetí la contraseña.'
  } else if (password.value !== passwordConfirm.value) {
    errs.passwordConfirm = 'Las contraseñas no coinciden.'
  }
  fieldErrors.value = errs
  return Object.keys(errs).length === 0
}

async function onSubmit() {
  error.value = null
  if (!validate()) {
    return
  }
  loading.value = true
  try {
    await authApi.register(email.value.trim(), nombre.value.trim(), password.value,
      username.value.trim())
    exito.value = true
  } catch (e) {
    if (axios.isAxiosError(e)) {
      const data = e.response?.data as { code?: string; message?: string } | undefined
      if (data?.code === 'EMAIL_YA_REGISTRADO') {
        error.value = 'Ese email ya tiene cuenta. Probá iniciar sesión.'
      } else if (data?.code === 'PASSWORD_DEBIL') {
        error.value = data.message ?? 'La contraseña es muy débil.'
      } else if (data?.code === 'RATE_LIMIT') {
        error.value = data.message ?? 'Demasiados registros. Esperá un rato.'
      } else {
        error.value = data?.message ?? 'No se pudo crear la cuenta.'
      }
    } else {
      error.value = 'Error de red. Probá de nuevo.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register-page">
    <div class="form-wrapper">
      <RouterLink to="/" class="brand">
        <ForsetiLogo variant="ink" :size="40" />
        <span class="forseti-wordmark brand-text">Forseti</span>
      </RouterLink>

      <template v-if="!exito">
        <div class="head">
          <h1 class="title">Crear cuenta</h1>
          <p class="sub">Empezá a usar Forseti — cumplimiento sin estrés.</p>
        </div>

        <form class="form" @submit.prevent="onSubmit">
          <div class="field">
            <label for="nombre">Tu nombre</label>
            <InputText id="nombre" v-model="nombre" required autocomplete="name"
              placeholder="Hernán Jurado" :disabled="loading" minlength="2" maxlength="200"
              :invalid="!!fieldErrors.nombre" />
            <small v-if="fieldErrors.nombre" class="field-error">{{ fieldErrors.nombre }}</small>
          </div>
          <div class="field">
            <label for="email">Correo electrónico</label>
            <InputText id="email" v-model="email" type="email" required autocomplete="email"
              placeholder="vos@empresa.ec" :disabled="loading"
              :invalid="!!fieldErrors.email" />
            <small v-if="fieldErrors.email" class="field-error">{{ fieldErrors.email }}</small>
          </div>
          <div class="field">
            <label for="username">Nombre de usuario para login</label>
            <InputText id="username" v-model="username" required autocomplete="username"
              placeholder="hernan_jurado" :disabled="loading"
              pattern="[a-z0-9._-]{3,40}" minlength="3" maxlength="40"
              :invalid="!!fieldErrors.username" />
            <small v-if="fieldErrors.username" class="field-error">{{ fieldErrors.username }}</small>
            <small v-else class="hint">3-40 caracteres: minúsculas, números, punto, guión o guión bajo. Vas a poder entrar con tu email o con este usuario.</small>
          </div>
          <div class="field">
            <label for="password">Contraseña (mín. 8 caracteres)</label>
            <Password id="password" v-model="password" required autocomplete="new-password"
              :feedback="true" toggle-mask :input-style="{ width: '100%' }" :disabled="loading"
              :invalid="!!fieldErrors.password" />
            <small v-if="fieldErrors.password" class="field-error">{{ fieldErrors.password }}</small>
          </div>
          <div class="field">
            <label for="passwordConfirm">Repetí la contraseña</label>
            <Password id="passwordConfirm" v-model="passwordConfirm" required autocomplete="new-password"
              :feedback="false" toggle-mask :input-style="{ width: '100%' }" :disabled="loading"
              :invalid="!!fieldErrors.passwordConfirm" />
            <small v-if="fieldErrors.passwordConfirm" class="field-error">{{ fieldErrors.passwordConfirm }}</small>
          </div>

          <Message v-if="error" severity="warn" :closable="false">{{ error }}</Message>

          <Button type="submit" label="Crear cuenta" icon="pi pi-arrow-right" icon-pos="right"
            :loading="loading" fluid />

          <p class="footer-text">
            ¿Ya tenés cuenta? <RouterLink to="/login">Iniciar sesión</RouterLink>
          </p>
        </form>
      </template>

      <template v-else>
        <div class="exito">
          <i class="pi pi-envelope" />
          <h2>Revisá tu correo</h2>
          <p>
            Te mandamos un email a <strong>{{ email }}</strong> con un link para verificar tu cuenta.
            El link expira en 24 horas.
          </p>
          <RouterLink to="/login" class="btn-primary">Volver a iniciar sesión</RouterLink>
        </div>
      </template>

      <div class="endorsement-wrapper">
        <EndorsementTarius />
      </div>
    </div>
  </div>
</template>

<style scoped>
.register-page { min-height: 100vh; background: var(--color-bg); display: flex; align-items: center; justify-content: center; padding: var(--sp-8); }
.form-wrapper { background: var(--color-surface); padding: var(--sp-12); border-radius: var(--radius-lg); border: 1px solid var(--color-border); width: 100%; max-width: 460px; box-shadow: var(--shadow-lg); }
.brand { display: inline-flex; align-items: center; gap: var(--sp-2); color: var(--color-ink); margin-bottom: var(--sp-8); }
.brand-text { font-size: var(--fs-xl); }
.head { margin-bottom: var(--sp-8); }
.title { font-size: var(--fs-2xl); letter-spacing: -0.02em; margin-bottom: var(--sp-3); }
.sub { color: var(--color-muted); }
.form { display: flex; flex-direction: column; gap: var(--sp-6); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-size: var(--fs-sm); font-weight: 500; }
.field :deep(.p-inputtext), .field :deep(.p-password input) { width: 100%; }
.hint { color: var(--color-muted); font-size: var(--fs-xs); }
.field-error { color: var(--color-danger); font-size: var(--fs-xs); }
.footer-text { text-align: center; color: var(--color-muted); font-size: var(--fs-sm); margin-top: var(--sp-2); }
.exito { text-align: center; padding: var(--sp-8) 0; }
.exito i { font-size: 48px; color: var(--color-marca); margin-bottom: var(--sp-6); }
.exito h2 { font-size: var(--fs-xl); margin-bottom: var(--sp-3); }
.exito p { color: var(--color-muted); line-height: 1.6; margin-bottom: var(--sp-8); }
.endorsement-wrapper { margin-top: var(--sp-8); text-align: center; }
</style>
