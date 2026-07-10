<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { empresaApi, type Establecimiento, type PuntoEmision } from '@/api/empresa'
import SecuencialesPunto from '@/components/empresa/SecuencialesPunto.vue'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'

const auth = useAuthStore()
const empresaId = computed(() => auth.empresaActivaId)

const establecimientos = ref<Establecimiento[]>([])
const puntosPorEstablecimiento = ref<Record<string, PuntoEmision[]>>({})
const error = ref<string | null>(null)
const loading = ref(true)

const nuevoEst = reactive({ codigo: '', nombre: '', direccion: '' })
const creando = ref(false)

const nuevoPunto = reactive<Record<string, { codigo: string; descripcion: string }>>({})

onMounted(async () => {
  if (!empresaId.value) {
    error.value = 'No hay empresa activa'
    loading.value = false
    return
  }
  await recargar()
})

async function recargar() {
  if (!empresaId.value) return
  loading.value = true
  try {
    establecimientos.value = await empresaApi.listarEstablecimientos(empresaId.value)
    for (const e of establecimientos.value) {
      puntosPorEstablecimiento.value[e.id] = await empresaApi.listarPuntos(empresaId.value, e.id)
      if (!nuevoPunto[e.id]) nuevoPunto[e.id] = { codigo: '', descripcion: '' }
    }
  } catch {
    error.value = 'No se pudo cargar la información'
  } finally {
    loading.value = false
  }
}

async function crearEstablecimiento() {
  if (!empresaId.value || !nuevoEst.codigo) return
  creando.value = true
  error.value = null
  try {
    await empresaApi.crearEstablecimiento(empresaId.value, nuevoEst.codigo, nuevoEst.nombre, nuevoEst.direccion)
    nuevoEst.codigo = ''
    nuevoEst.nombre = ''
    nuevoEst.direccion = ''
    await recargar()
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'No se pudo crear el establecimiento'
  } finally {
    creando.value = false
  }
}

async function crearPunto(establecimientoId: string) {
  if (!empresaId.value) return
  const data = nuevoPunto[establecimientoId]
  if (!data?.codigo) return
  error.value = null
  try {
    await empresaApi.crearPunto(empresaId.value, establecimientoId, data.codigo, data.descripcion)
    nuevoPunto[establecimientoId] = { codigo: '', descripcion: '' }
    await recargar()
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    error.value = err.response?.data?.message ?? 'No se pudo crear el punto de emisión'
  }
}
</script>

<template>
  <div class="establecimientos">
    <header class="page-head">
      <h1>Establecimientos y puntos de emisión</h1>
      <p class="subtitulo">
        Cada empresa tiene 1..N establecimientos (códigos SRI 001, 002…), y cada establecimiento
        sus puntos de emisión. El secuencial de cada tipo de comprobante (factura, NC, …) se
        inicializa en 1 al crear el punto y se asigna transaccionalmente al emitir.
      </p>
    </header>

    <Message v-if="error" severity="error">{{ error }}</Message>

    <div v-if="loading" class="loading">Cargando…</div>

    <section v-else class="lista">
      <Card v-for="est in establecimientos" :key="est.id" class="est">
        <template #title>
          <span class="codigo">{{ est.codigo }}</span> · {{ est.nombre ?? '(sin nombre)' }}
        </template>
        <template #subtitle>{{ est.direccion ?? '—' }}</template>
        <template #content>
          <h3>Puntos de emisión</h3>
          <ul class="puntos">
            <li v-for="p in puntosPorEstablecimiento[est.id] ?? []" :key="p.id" class="punto-row">
              <div class="punto-head">
                <span class="codigo">{{ p.codigo }}</span>
                <span class="desc">{{ p.descripcion ?? '—' }}</span>
              </div>
              <SecuencialesPunto :empresa-id="empresaId!"
                                 :establecimiento-id="est.id"
                                 :punto-emision-id="p.id"
                                 :punto-codigo="p.codigo" />
            </li>
            <li v-if="(puntosPorEstablecimiento[est.id] ?? []).length === 0" class="vacio">
              Sin puntos todavía.
            </li>
          </ul>
          <form class="form-punto" @submit.prevent="crearPunto(est.id)">
            <InputText v-model="nuevoPunto[est.id].codigo" placeholder="001" maxlength="3" />
            <InputText v-model="nuevoPunto[est.id].descripcion" placeholder="Caja principal" />
            <Button type="submit" icon="pi pi-plus" label="Agregar punto" size="small" />
          </form>
        </template>
      </Card>

      <Card class="nuevo">
        <template #title>Nuevo establecimiento</template>
        <template #content>
          <form class="form-est" @submit.prevent="crearEstablecimiento">
            <div class="field">
              <label>Código (3 dígitos)</label>
              <InputText v-model="nuevoEst.codigo" placeholder="001" maxlength="3" required />
            </div>
            <div class="field">
              <label>Nombre</label>
              <InputText v-model="nuevoEst.nombre" placeholder="Matriz" />
            </div>
            <div class="field">
              <label>Dirección</label>
              <InputText v-model="nuevoEst.direccion" placeholder="Av. Amazonas N32-50" />
            </div>
            <Button type="submit" :loading="creando" label="Crear establecimiento" icon="pi pi-check" />
          </form>
        </template>
      </Card>
    </section>
  </div>
</template>

<style scoped>
.establecimientos { max-width: 900px; margin: 0 auto; }
.page-head { margin-bottom: var(--sp-6); }
.page-head h1 { font-family: var(--font-display); }
.subtitulo { color: var(--color-muted); }
.loading { text-align: center; padding: var(--sp-8); color: var(--color-muted); }
.lista { display: flex; flex-direction: column; gap: var(--sp-4); }
.codigo { font-family: var(--font-mono); background: var(--color-marca-bg); padding: 2px 6px; border-radius: var(--radius-sm); color: var(--color-marca-deep); }
.puntos { list-style: none; padding: 0; display: flex; flex-direction: column; gap: var(--sp-2); margin-bottom: var(--sp-3); }
.puntos li { display: flex; gap: var(--sp-3); padding: var(--sp-2) var(--sp-3); background: var(--color-bg); border-radius: var(--radius-sm); }
.desc { color: var(--color-muted); }
.vacio { color: var(--color-muted); font-style: italic; }
.form-punto, .form-est { display: flex; flex-direction: column; gap: var(--sp-3); }
.form-punto { flex-direction: row; align-items: end; }
.field { display: flex; flex-direction: column; gap: var(--sp-1); }
.field label { font-size: var(--fs-sm); font-weight: 500; }
.nuevo { border: 2px dashed var(--color-marca); }
</style>
