<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { authApi } from '@/api/auth'
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'
import ProgressSpinner from 'primevue/progressspinner'

const route = useRoute()
const estado = ref<'loading' | 'ok' | 'error'>('loading')
const error = ref<string | null>(null)

onMounted(async () => {
  const token = route.query.token as string | undefined
  if (!token) {
    estado.value = 'error'
    error.value = 'Link sin token. Pedí uno nuevo desde "¿Olvidaste la contraseña?".'
    return
  }
  try {
    await authApi.verifyEmail(token)
    estado.value = 'ok'
  } catch {
    estado.value = 'error'
    error.value = 'Link inválido o expirado. Pedí uno nuevo desde Registro o Login.'
  }
})
</script>

<template>
  <div class="verify-page">
    <div class="card">
      <RouterLink to="/" class="brand">
        <ForsetiLogo variant="ink" :size="40" />
        <span class="forseti-wordmark brand-text">Forseti</span>
      </RouterLink>

      <div class="content">
        <template v-if="estado === 'loading'">
          <ProgressSpinner style="width:48px;height:48px" />
          <h2>Verificando…</h2>
        </template>

        <template v-else-if="estado === 'ok'">
          <i class="pi pi-check-circle success" />
          <h2>¡Cuenta activada!</h2>
          <p>Ya podés iniciar sesión.</p>
          <RouterLink to="/login" class="btn-primary">Entrar</RouterLink>
        </template>

        <template v-else>
          <i class="pi pi-times-circle danger" />
          <h2>No se pudo verificar</h2>
          <p>{{ error }}</p>
          <RouterLink to="/registro" class="btn-ghost">Volver a registro</RouterLink>
        </template>
      </div>

      <div class="endorsement-wrapper">
        <EndorsementTarius />
      </div>
    </div>
  </div>
</template>

<style scoped>
.verify-page { min-height: 100vh; background: var(--color-bg); display: flex; align-items: center; justify-content: center; padding: var(--sp-8); }
.card { background: var(--color-surface); padding: var(--sp-12); border-radius: var(--radius-lg); border: 1px solid var(--color-border); width: 100%; max-width: 460px; box-shadow: var(--shadow-lg); text-align: center; }
.brand { display: inline-flex; align-items: center; gap: var(--sp-2); color: var(--color-ink); margin-bottom: var(--sp-8); }
.brand-text { font-size: var(--fs-xl); }
.content { padding: var(--sp-8) 0; }
.content i { font-size: 56px; margin-bottom: var(--sp-6); display: block; }
.content i.success { color: var(--color-success); }
.content i.danger { color: var(--color-danger); }
.content h2 { font-size: var(--fs-xl); margin-bottom: var(--sp-3); }
.content p { color: var(--color-muted); line-height: 1.6; margin-bottom: var(--sp-8); }
.endorsement-wrapper { margin-top: var(--sp-8); text-align: center; }
</style>
