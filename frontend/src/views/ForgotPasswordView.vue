<script setup lang="ts">
import { ref } from 'vue'
import { authApi } from '@/api/auth'
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'

const email = ref('')
const loading = ref(false)
const enviado = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    await authApi.recovery(email.value)
    // SIEMPRE muestra "enviado" (anti-enum de emails)
    enviado.value = true
  } catch {
    // Ignorar — siempre mostrar éxito por privacidad
    enviado.value = true
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

      <template v-if="!enviado">
        <div class="head">
          <h1 class="title">Recuperar contraseña</h1>
          <p class="sub">Pegá tu email y te enviamos un link para resetearla.</p>
        </div>

        <form class="form" @submit.prevent="onSubmit">
          <div class="field">
            <label for="email">Correo electrónico</label>
            <InputText id="email" v-model="email" type="email" required autocomplete="email"
              :disabled="loading" />
          </div>
          <Button type="submit" label="Enviar link" :loading="loading" fluid />
          <p class="back">
            <RouterLink to="/login">← Volver a login</RouterLink>
          </p>
        </form>
      </template>

      <template v-else>
        <div class="exito">
          <i class="pi pi-envelope" />
          <h2>Si la cuenta existe, te llegó el link</h2>
          <p>Revisá tu correo (y el spam). El link expira en 30 minutos.</p>
          <RouterLink to="/login" class="btn-primary">Volver a login</RouterLink>
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
.field :deep(.p-inputtext) { width: 100%; }
.back { text-align: center; color: var(--color-muted); font-size: var(--fs-sm); }
.exito { text-align: center; padding: var(--sp-6) 0; }
.exito i { font-size: 48px; color: var(--color-marca); margin-bottom: var(--sp-6); }
.exito h2 { font-size: var(--fs-lg); margin-bottom: var(--sp-3); }
.exito p { color: var(--color-muted); line-height: 1.6; margin-bottom: var(--sp-8); }
.endorsement-wrapper { margin-top: var(--sp-8); text-align: center; }
</style>
