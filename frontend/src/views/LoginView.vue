<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import axios from 'axios'
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Message from 'primevue/message'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const email = ref('')
const password = ref('')
const otp = ref('')
const requiere2FA = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)

async function onSubmit() {
  error.value = null
  loading.value = true
  try {
    const { requiere2FA: r } = await auth.login(email.value, password.value, otp.value || undefined)
    if (r && !requiere2FA.value) {
      requiere2FA.value = true
      return
    }
    const redirect = route.query.redirect as string | undefined
    await router.push(redirect || '/app')
  } catch (e) {
    if (axios.isAxiosError(e)) {
      const data = e.response?.data as { code?: string; message?: string } | undefined
      if (data?.code === 'TOTP_INVALIDO') {
        error.value = 'Código 2FA inválido.'
      } else if (data?.code === 'EMAIL_NO_VERIFICADO') {
        error.value = 'Tenés que verificar tu email primero. Revisá tu bandeja.'
      } else if (data?.code === 'CUENTA_BLOQUEADA') {
        error.value = 'Cuenta bloqueada temporalmente. Esperá 15 minutos.'
      } else if (data?.code === 'RATE_LIMIT') {
        error.value = data.message ?? 'Demasiados intentos. Esperá un rato.'
      } else if (e.response?.status === 401) {
        error.value = 'Identificador o contraseña incorrectos.'
      } else {
        error.value = data?.message ?? 'No se pudo iniciar sesión.'
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
  <div class="login-page">
    <div class="login-left">
      <div class="login-form-wrapper">
        <RouterLink to="/" class="brand">
          <ForsetiLogo variant="ink" :size="40" />
          <span class="forseti-wordmark brand-text">Forseti</span>
        </RouterLink>

        <div class="login-head">
          <h1 class="login-title">{{ requiere2FA ? 'Verificación 2FA' : 'Iniciar sesión' }}</h1>
          <p class="login-sub">
            {{ requiere2FA
              ? 'Abrí tu app de autenticación (Google Authenticator, Authy) e ingresá el código.'
              : 'Accedé a tu panel de Forseti.' }}
          </p>
        </div>

        <form class="login-form" @submit.prevent="onSubmit">
          <template v-if="!requiere2FA">
            <div class="field">
              <label for="email">Email o nombre de usuario</label>
              <InputText id="email" v-model="email" type="text" required autocomplete="username"
                placeholder="vos@empresa.ec ó tu_usuario" :disabled="loading" />
            </div>

            <div class="field">
              <div class="field-header">
                <label for="password">Contraseña</label>
                <RouterLink to="/recuperar" class="field-link">¿La olvidaste?</RouterLink>
              </div>
              <Password id="password" v-model="password" required autocomplete="current-password"
                :feedback="false" toggle-mask :input-style="{ width: '100%' }" :disabled="loading" />
            </div>
          </template>

          <template v-else>
            <div class="field">
              <label for="otp">Código 6 dígitos</label>
              <InputText id="otp" v-model="otp" required autocomplete="one-time-code"
                inputmode="numeric" pattern="[0-9]{6}" maxlength="6" placeholder="123456"
                :disabled="loading" class="otp-input" />
            </div>
            <Button label="Cancelar" severity="secondary" text @click="requiere2FA = false; otp = ''" />
          </template>

          <Message v-if="error" severity="warn" :closable="false" class="login-error">{{ error }}</Message>

          <Button type="submit" :label="requiere2FA ? 'Verificar' : 'Entrar'"
            icon="pi pi-arrow-right" icon-pos="right" :loading="loading" class="login-submit" fluid />

          <p class="login-footer-text">
            ¿No tenés cuenta?
            <RouterLink to="/registro">Crear cuenta</RouterLink>
          </p>
        </form>

        <div class="login-endorsement">
          <EndorsementTarius />
        </div>
      </div>
    </div>

    <div class="login-right">
      <div class="login-right-content">
        <blockquote class="quote">
          <p class="quote-text">
            "Cumplir con el SRI no debería ser perder un sábado al mes
            buscando un comprobante en Excel."
          </p>
          <footer class="quote-meta">
            <span class="quote-author">Hernán Jurado</span>
            <span class="quote-role">fundador de TARIUS · primer usuario de Forseti</span>
          </footer>
        </blockquote>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: 1fr 1fr; }
.login-left { display: flex; align-items: center; justify-content: center; padding: var(--sp-8); background: var(--color-surface); }
.login-form-wrapper { width: 100%; max-width: 420px; }
.brand { display: inline-flex; align-items: center; gap: var(--sp-2); color: var(--color-ink); margin-bottom: var(--sp-12); }
.brand-text { font-size: var(--fs-xl); }
.login-head { margin-bottom: var(--sp-8); }
.login-title { font-size: var(--fs-2xl); letter-spacing: -0.02em; margin-bottom: var(--sp-3); }
.login-sub { color: var(--color-muted); }
.login-form { display: flex; flex-direction: column; gap: var(--sp-6); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field-header { display: flex; justify-content: space-between; align-items: center; }
.field label { font-size: var(--fs-sm); font-weight: 500; color: var(--color-ink); }
.field-link { font-size: var(--fs-xs); color: var(--color-action); }
.field :deep(.p-inputtext), .field :deep(.p-password input) { width: 100%; }
.otp-input { font-family: var(--font-mono); font-size: var(--fs-xl); text-align: center; letter-spacing: 0.5em; }
.login-error { margin: 0; }
.login-submit { margin-top: var(--sp-2); }
.login-footer-text { text-align: center; color: var(--color-muted); font-size: var(--fs-sm); margin-top: var(--sp-2); }
.login-endorsement { margin-top: var(--sp-12); text-align: center; }
.login-right { background: linear-gradient(135deg, var(--color-marca) 0%, var(--color-marca-deep) 100%); color: white; display: flex; align-items: center; justify-content: center; padding: var(--sp-12); position: relative; overflow: hidden; }
.login-right::before { content: ''; position: absolute; top: -50%; right: -10%; width: 70%; height: 200%; background: radial-gradient(circle, rgba(255,255,255,0.08) 0%, transparent 70%); pointer-events: none; }
.login-right-content { max-width: 480px; position: relative; z-index: 1; }
.quote-text { font-family: var(--font-display); font-size: var(--fs-2xl); font-style: italic; line-height: 1.3; letter-spacing: -0.02em; margin-bottom: var(--sp-8); color: white; }
.quote-meta { display: flex; flex-direction: column; gap: var(--sp-1); }
.quote-author { font-weight: 600; font-size: var(--fs-md); }
.quote-role { font-size: var(--fs-sm); opacity: 0.85; }
@media (max-width: 900px) { .login-page { grid-template-columns: 1fr; } .login-right { display: none; } }
</style>
