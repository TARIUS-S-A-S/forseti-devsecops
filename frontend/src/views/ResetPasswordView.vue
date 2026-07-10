<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { authApi } from '@/api/auth'
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'
import Password from 'primevue/password'
import Button from 'primevue/button'
import Message from 'primevue/message'

const route = useRoute()
const router = useRouter()
const password = ref('')
const password2 = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const ok = ref(false)

const token = computed(() => (route.query.token as string | undefined) ?? '')
const passwordsCoinciden = computed(() => password.value === password2.value && password.value.length >= 8)

async function onSubmit() {
  error.value = null
  if (!token.value) {
    error.value = 'Link sin token. Pedí uno nuevo desde "Olvidé contraseña".'
    return
  }
  if (!passwordsCoinciden.value) {
    error.value = 'Las contraseñas no coinciden o son menores a 8 caracteres.'
    return
  }
  loading.value = true
  try {
    await authApi.resetPassword(token.value, password.value)
    ok.value = true
    setTimeout(() => router.push({ name: 'login' }), 2500)
  } catch (e) {
    if (axios.isAxiosError(e)) {
      const code = (e.response?.data as { code?: string } | undefined)?.code
      error.value = code === 'TOKEN_EXPIRADO' ? 'El link expiró. Pedí uno nuevo.'
                  : code === 'TOKEN_INVALIDO' ? 'Link inválido.'
                  : 'No se pudo cambiar la contraseña.'
    } else {
      error.value = 'Error de red.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <RouterLink to="/" class="brand">
        <ForsetiLogo variant="ink" :size="40" />
        <span class="forseti-wordmark brand-text">Forseti</span>
      </RouterLink>

      <template v-if="!ok">
        <div class="head">
          <h1 class="title">Cambiar contraseña</h1>
          <p class="sub">Mínimo 8 caracteres.</p>
        </div>

        <form class="form" @submit.prevent="onSubmit">
          <div class="field">
            <label for="p1">Nueva contraseña</label>
            <Password id="p1" v-model="password" required :feedback="true" toggle-mask
              :input-style="{ width: '100%' }" :disabled="loading" />
          </div>
          <div class="field">
            <label for="p2">Repetir</label>
            <Password id="p2" v-model="password2" required :feedback="false" toggle-mask
              :input-style="{ width: '100%' }" :disabled="loading" />
          </div>

          <Message v-if="error" severity="warn" :closable="false">{{ error }}</Message>

          <Button type="submit" label="Cambiar contraseña" :loading="loading"
            :disabled="!passwordsCoinciden" fluid />
        </form>
      </template>

      <template v-else>
        <div class="exito">
          <i class="pi pi-check-circle" />
          <h2>Contraseña actualizada</h2>
          <p>Redirigiendo a login…</p>
        </div>
      </template>

      <div class="endorsement-wrapper">
        <EndorsementTarius />
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--color-bg); display: flex; align-items: center; justify-content: center; padding: var(--sp-8); }
.card { background: var(--color-surface); padding: var(--sp-12); border-radius: var(--radius-lg); border: 1px solid var(--color-border); width: 100%; max-width: 420px; box-shadow: var(--shadow-lg); }
.brand { display: inline-flex; align-items: center; gap: var(--sp-2); color: var(--color-ink); margin-bottom: var(--sp-8); }
.brand-text { font-size: var(--fs-xl); }
.head { margin-bottom: var(--sp-8); }
.title { font-size: var(--fs-xl); margin-bottom: var(--sp-3); }
.sub { color: var(--color-muted); }
.form { display: flex; flex-direction: column; gap: var(--sp-6); }
.field { display: flex; flex-direction: column; gap: var(--sp-2); }
.field label { font-size: var(--fs-sm); font-weight: 500; }
.field :deep(.p-password input) { width: 100%; }
.exito { text-align: center; padding: var(--sp-6) 0; }
.exito i { font-size: 48px; color: var(--color-success); margin-bottom: var(--sp-6); }
.exito h2 { font-size: var(--fs-lg); margin-bottom: var(--sp-3); }
.exito p { color: var(--color-muted); }
.endorsement-wrapper { margin-top: var(--sp-8); text-align: center; }
</style>
