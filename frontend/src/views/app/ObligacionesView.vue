<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { obligacionApi, type ObligacionCatalogo, type ObligacionEmpresa } from '@/api/obligacion'
import InputSwitch from 'primevue/toggleswitch'
import Tag from 'primevue/tag'
import Message from 'primevue/message'

const auth = useAuthStore()

const catalogo = ref<ObligacionCatalogo[]>([])
const activadas = ref<ObligacionEmpresa[]>([])
const sugeridas = ref<ObligacionCatalogo[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const empresaId = computed(() => auth.empresaActivaId)

const activadasMap = computed(() => {
  const map = new Map<string, ObligacionEmpresa>()
  for (const o of activadas.value) map.set(o.obligacionCodigo, o)
  return map
})

const sugeridasSet = computed(() => new Set(sugeridas.value.map(s => s.codigo)))

const porCategoria = computed(() => {
  const groups: Record<string, ObligacionCatalogo[]> = {}
  for (const o of catalogo.value) {
    if (!groups[o.categoria]) groups[o.categoria] = []
    groups[o.categoria].push(o)
  }
  return groups
})

const labelCategoria: Record<string, string> = {
  SRI_DECLARACION: 'SRI — Declaraciones',
  SRI_ANEXO: 'SRI — Anexos',
  SUPERCIA: 'Superintendencia de Compañías',
  MUNICIPIO: 'Municipio',
  IESS_MDT: 'IESS / Ministerio de Trabajo',
  INTERNA: 'Internas',
}

onMounted(async () => {
  if (!empresaId.value) {
    error.value = 'No hay empresa activa'
    loading.value = false
    return
  }
  try {
    const [cat, act, sug] = await Promise.all([
      obligacionApi.catalogo(),
      obligacionApi.activadas(empresaId.value),
      obligacionApi.sugeridas(empresaId.value),
    ])
    catalogo.value = cat
    activadas.value = act
    sugeridas.value = sug
  } catch {
    error.value = 'No se pudo cargar el catálogo de obligaciones'
  } finally {
    loading.value = false
  }
})

async function toggle(codigo: string, valor: boolean) {
  if (!empresaId.value) return
  try {
    if (valor) {
      const nueva = await obligacionApi.activar(empresaId.value, codigo)
      const existente = activadasMap.value.get(codigo)
      if (existente) {
        existente.activa = true
      } else {
        activadas.value.push(nueva)
      }
    } else {
      await obligacionApi.desactivar(empresaId.value, codigo)
      const existente = activadasMap.value.get(codigo)
      if (existente) existente.activa = false
    }
  } catch {
    error.value = 'No se pudo actualizar la obligación'
  }
}

function estaActiva(codigo: string): boolean {
  return activadasMap.value.get(codigo)?.activa === true
}
</script>

<template>
  <div class="obligaciones">
    <header class="page-head">
      <h1>Obligaciones</h1>
      <p class="subtitulo">
        Catálogo completo de obligaciones tributarias y regulatorias de Ecuador. Activá
        las que apliquen a esta empresa — Forseti las va a llevar en el calendario y va a generarte
        los formularios/anexos cuando toque.
      </p>
    </header>

    <Message v-if="error" severity="error">{{ error }}</Message>

    <div v-if="loading" class="loading">Cargando catálogo…</div>

    <section v-for="(items, cat) in porCategoria" v-else :key="cat" class="cat">
      <h2>{{ labelCategoria[cat] ?? cat }}</h2>
      <ul>
        <li v-for="o in items" :key="o.codigo" class="obligacion">
          <div class="info">
            <div class="title-row">
              <strong>{{ o.nombre }}</strong>
              <Tag v-if="sugeridasSet.has(o.codigo)" value="Sugerida" severity="info" />
              <Tag v-if="o.bloqueante" value="Bloqueante" severity="warn" />
            </div>
            <p class="descripcion">{{ o.descripcion }}</p>
            <p class="meta">
              <span><strong>Periodicidad:</strong> {{ o.periodicidad }}</span>
              <span v-if="o.reglaFecha"><strong>Regla:</strong> {{ o.reglaFecha }}</span>
            </p>
          </div>
          <InputSwitch :model-value="estaActiva(o.codigo)"
                       @update:model-value="(v) => toggle(o.codigo, v as boolean)" />
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.obligaciones { max-width: 900px; margin: 0 auto; }
.page-head { margin-bottom: var(--sp-6); }
.page-head h1 { font-family: var(--font-display); }
.subtitulo { color: var(--color-muted); }
.loading { text-align: center; padding: var(--sp-8); color: var(--color-muted); }
.cat { margin-bottom: var(--sp-6); }
.cat h2 { font-family: var(--font-display); font-size: var(--fs-lg); margin-bottom: var(--sp-3); padding-bottom: var(--sp-2); border-bottom: 2px solid var(--color-marca); }
.cat ul { list-style: none; padding: 0; display: flex; flex-direction: column; gap: var(--sp-2); }
.obligacion { display: flex; justify-content: space-between; align-items: center; gap: var(--sp-4); padding: var(--sp-4); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.info { flex: 1; }
.title-row { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-1); }
.descripcion { color: var(--color-muted); font-size: var(--fs-sm); margin: 0 0 var(--sp-2) 0; }
.meta { display: flex; gap: var(--sp-4); font-size: var(--fs-xs); color: var(--color-muted); margin: 0; }
</style>
