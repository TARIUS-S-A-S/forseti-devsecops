<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import Button from 'primevue/button'

const router = useRouter()
const auth = useAuthStore()

async function elegir(id: string) {
  await auth.cambiarEmpresaActiva(id)
  router.push({ name: 'dashboard' })
}
</script>

<template>
  <div class="selector">
    <h1>Elegí una empresa</h1>
    <p class="subtitulo">Vas a operar Forseti dentro de la empresa que selecciones. Podés cambiar de empresa desde el menú superior.</p>

    <ul class="empresas">
      <li v-for="e in auth.user?.empresas ?? []" :key="e.id" class="empresa">
        <div class="info">
          <strong>{{ e.razonSocial }}</strong>
          <span class="rol">{{ e.rol }}</span>
        </div>
        <Button label="Usar esta" @click="elegir(e.id)" />
      </li>
    </ul>

    <div class="nueva">
      <Button label="Crear nueva empresa" icon="pi pi-plus" severity="secondary"
              @click="router.push({ name: 'empresa-nueva' })" />
    </div>
  </div>
</template>

<style scoped>
.selector { max-width: 600px; margin: 0 auto; }
.subtitulo { color: var(--color-muted); margin-bottom: var(--sp-6); }
.empresas { list-style: none; padding: 0; display: flex; flex-direction: column; gap: var(--sp-3); margin-bottom: var(--sp-6); }
.empresa { display: flex; justify-content: space-between; align-items: center; padding: var(--sp-4); border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); }
.info { display: flex; flex-direction: column; gap: var(--sp-1); }
.rol { font-size: var(--fs-xs); color: var(--color-muted); text-transform: uppercase; letter-spacing: 0.05em; }
.nueva { display: flex; justify-content: center; }
</style>
