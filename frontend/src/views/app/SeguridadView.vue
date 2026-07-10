<script setup lang="ts">
import { ref, computed } from 'vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import axios from 'axios'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ToggleSwitch from 'primevue/toggleswitch'

const auth = useAuthStore()

const setupData = ref<{ secret: string; otpAuthUri: string; qrPngBase64: string } | null>(null)
const code = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const ok = ref(false)

const tieneTotp = computed(() => auth.user?.tieneTotp ?? false)
const totpLoginRequired = computed(() => auth.user?.totpLoginRequired ?? true)
const togglingLoginReq = ref(false)

async function toggleLoginRequired(value: boolean) {
  togglingLoginReq.value = true
  try {
    await authApi.setTotpLoginRequired(value)
    await auth.refrescar()
  } catch {
    error.value = 'No se pudo actualizar la preferencia.'
  } finally {
    togglingLoginReq.value = false
  }
}

async function iniciarSetup() {
  error.value = null
  loading.value = true
  try {
    setupData.value = await authApi.setup2FA()
  } catch (e) {
    error.value = axios.isAxiosError(e) ? 'No se pudo iniciar la configuración 2FA.' : 'Error de red.'
  } finally {
    loading.value = false
  }
}

async function confirmar() {
  if (!setupData.value || code.value.length !== 6) return
  error.value = null
  loading.value = true
  try {
    await authApi.confirm2FA(setupData.value.secret, code.value)
    ok.value = true
    await auth.fetchUser()
    setupData.value = null
    code.value = ''
  } catch (e) {
    if (axios.isAxiosError(e)) {
      const data = e.response?.data as { code?: string } | undefined
      error.value = data?.code === 'TOTP_INVALIDO' ? 'Código incorrecto. Volvé a probar.' : 'No se pudo confirmar.'
    } else {
      error.value = 'Error de red.'
    }
  } finally {
    loading.value = false
  }
}

async function desactivar() {
  if (!confirm('¿Seguro de desactivar 2FA? Tu cuenta quedará menos protegida.')) return
  loading.value = true
  try {
    await authApi.disable2FA()
    await auth.fetchUser()
    ok.value = false
  } catch {
    error.value = 'No se pudo desactivar.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="seguridad">
    <h1 class="title">Seguridad de la cuenta</h1>

    <section class="card">
      <div class="card-head">
        <h2>Autenticación en 2 pasos (2FA)</h2>
        <span v-if="tieneTotp" class="badge ok">Activa</span>
        <span v-else class="badge off">Desactivada</span>
      </div>

      <p class="desc">
        Agrega una capa extra de seguridad a tu cuenta. Cada vez que inicies sesión, te vamos a pedir
        un código de 6 dígitos generado por una app como Google Authenticator, Authy o 1Password.
      </p>

      <!-- ACTIVA: opción de pausar login + desactivar completo -->
      <template v-if="tieneTotp && !setupData">
        <Message v-if="totpLoginRequired" severity="success" :closable="false">
          2FA está activo. Cuando inicies sesión, te vamos a pedir el código de tu app.
        </Message>
        <Message v-else severity="warn" :closable="false">
          2FA configurado pero <strong>pausado para login</strong>. Tu secret sigue guardado, no vas a tener que volver a escanear el QR.
        </Message>

        <div class="toggle-row">
          <div class="toggle-text">
            <strong>Pedir código al iniciar sesión</strong>
            <small>Si lo apagás, podés volver a prenderlo sin reconfigurar.</small>
          </div>
          <ToggleSwitch :model-value="totpLoginRequired" :disabled="togglingLoginReq"
                        @update:model-value="(v) => toggleLoginRequired(v as boolean)" />
        </div>

        <Button label="Desactivar 2FA completamente" severity="danger" outlined
                :loading="loading" @click="desactivar" />
        <small class="hint-desactivar">Esto borra el secret. Para volver a activar vas a tener que escanear el QR de nuevo.</small>
      </template>

      <!-- INACTIVA: opción de configurar -->
      <template v-else-if="!setupData && !ok">
        <Button label="Configurar 2FA" icon="pi pi-shield" :loading="loading" @click="iniciarSetup" />
      </template>

      <!-- CONFIGURANDO: QR + input -->
      <template v-else-if="setupData">
        <div class="setup">
          <div class="setup-step">
            <strong>1.</strong> Abrí tu app de autenticación (Google Authenticator, Authy, 1Password, etc.).
          </div>
          <div class="setup-step">
            <strong>2.</strong> Escaneá este QR:
            <div class="qr-wrap">
              <img :src="setupData.qrPngBase64" alt="QR 2FA" class="qr" />
            </div>
            <small>O agregá manualmente el código: <code class="secret">{{ setupData.secret }}</code></small>
          </div>
          <div class="setup-step">
            <strong>3.</strong> Ingresá el código de 6 dígitos que muestra la app:
            <InputText v-model="code" maxlength="6" inputmode="numeric" pattern="[0-9]{6}"
              placeholder="123456" class="code-input" :disabled="loading" />
          </div>

          <Message v-if="error" severity="warn" :closable="false">{{ error }}</Message>

          <div class="actions">
            <Button label="Confirmar" :loading="loading" :disabled="code.length !== 6" @click="confirmar" />
            <Button label="Cancelar" text @click="setupData = null; code = ''; error = null" />
          </div>
        </div>
      </template>

      <Message v-if="ok && !tieneTotp" severity="success" :closable="false">
        ¡2FA activado! La próxima vez que inicies sesión te vamos a pedir el código.
      </Message>
    </section>
  </div>
</template>

<style scoped>
.seguridad { max-width: 720px; margin: 0 auto; }
.title { font-size: var(--fs-2xl); letter-spacing: -0.02em; margin-bottom: var(--sp-6); }
.card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-lg); padding: var(--sp-8); }
.card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--sp-4); }
.card-head h2 { font-size: var(--fs-lg); }
.badge { font-size: var(--fs-xs); padding: 4px 10px; border-radius: var(--radius-full); font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; }
.badge.ok { background: var(--color-success); color: white; }
.badge.off { background: var(--color-border); color: var(--color-muted); }
.desc { color: var(--color-muted); line-height: 1.6; margin-bottom: var(--sp-6); }
.setup { display: flex; flex-direction: column; gap: var(--sp-6); margin-top: var(--sp-6); }
.setup-step { line-height: 1.6; }
.qr-wrap { margin: var(--sp-4) 0; display: flex; justify-content: center; }
.qr { width: 200px; height: 200px; padding: var(--sp-2); background: white; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.secret { font-family: var(--font-mono); font-size: var(--fs-sm); background: var(--color-bg); padding: 2px 6px; border-radius: var(--radius-sm); user-select: all; }
.code-input { font-family: var(--font-mono); font-size: var(--fs-xl); text-align: center; letter-spacing: 0.5em; width: 100%; margin-top: var(--sp-2); }
.actions { display: flex; gap: var(--sp-3); }
.toggle-row { display: flex; justify-content: space-between; align-items: center; gap: var(--sp-4); padding: var(--sp-4); background: var(--color-bg); border-radius: var(--radius-md); margin: var(--sp-4) 0; }
.toggle-text { display: flex; flex-direction: column; gap: var(--sp-1); }
.toggle-text small { color: var(--color-muted); font-size: var(--fs-xs); }
.hint-desactivar { color: var(--color-muted); font-size: var(--fs-xs); display: block; margin-top: var(--sp-2); }
</style>
