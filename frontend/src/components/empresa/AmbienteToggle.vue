<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { empresaApi, type Empresa } from '@/api/empresa'
import { mensajeDeError } from '@/composables/useApiError'
import { useAuthStore } from '@/stores/auth'
import Tag from 'primevue/tag'
import Button from 'primevue/button'
import Message from 'primevue/message'
import Dialog from 'primevue/dialog'
import ConfirmDialog from 'primevue/confirmdialog'
import { useConfirm } from 'primevue/useconfirm'

const props = defineProps<{ empresa: Empresa | null }>()
const emit = defineEmits<{ cambiado: [Empresa] }>()
const auth = useAuthStore()
const router = useRouter()
const confirm = useConfirm()

const cambiando = ref(false)
const error = ref<string | null>(null)
const ok = ref<string | null>(null)

const esProduccion = computed(() => props.empresa?.ambienteDefault === 'PRODUCCION')

/* ─── Pre-flight para pasar a PRODUCCION ─── */
interface PreflightCheck {
  label: string
  cumple: boolean
  detalle: string
  accion: { label: string; ruta: string } | null
}

const dialogPreflight = ref(false)
const cargandoPreflight = ref(false)
const checks = ref<PreflightCheck[]>([])

const todosCumplen = computed(() => checks.value.length > 0 && checks.value.every(c => c.cumple))

async function correrPreflight() {
  if (!props.empresa) return
  const empresaId = props.empresa.id
  cargandoPreflight.value = true
  checks.value = []
  dialogPreflight.value = true
  try {
    // 3 llamadas en paralelo
    const [certActivo, perfil, establecimientos] = await Promise.all([
      empresaApi.certificadoActivo(empresaId).catch(() => null),
      empresaApi.perfilVigente(empresaId).catch(() => null),
      empresaApi.listarEstablecimientos(empresaId).catch(() => [] as Awaited<ReturnType<typeof empresaApi.listarEstablecimientos>>),
    ])

    // Check 1 — certificado activo
    const checkCert: PreflightCheck = certActivo
      ? {
          label: 'Certificado de firma activo',
          cumple: true,
          detalle: `${certActivo.sujetoCn ?? 'Sin CN'} · vence en ${certActivo.diasParaCaducar} días`,
          accion: null,
        }
      : {
          label: 'Certificado de firma activo',
          cumple: false,
          detalle: 'Ningún certificado .p12 está activo en esta empresa.',
          accion: { label: 'Cargar certificado', ruta: '/app/empresa/configuracion?tab=firma' },
        }

    // Check 2 — perfil tributario vigente
    const checkPerfil: PreflightCheck = perfil
      ? {
          label: 'Perfil tributario vigente',
          cumple: true,
          detalle: `${perfil.regimenTributario} · IVA ${perfil.periodicidadIva} · vigente desde ${perfil.vigenteDesde}`,
          accion: null,
        }
      : {
          label: 'Perfil tributario vigente',
          cumple: false,
          detalle: 'Esta empresa no tiene un perfil tributario configurado.',
          accion: { label: 'Configurar perfil', ruta: '/app/empresa/configuracion?tab=datos' },
        }

    // Check 3 — al menos un secuencial PRODUCCION
    let hayProd = false
    let detalleSec = ''
    if (establecimientos.length === 0) {
      detalleSec = 'No hay establecimientos creados.'
    } else {
      const todosPuntos = await Promise.all(
        establecimientos.map(e => empresaApi.listarPuntos(empresaId, e.id).catch(() => [])),
      )
      const puntos = todosPuntos.flat()
      if (puntos.length === 0) {
        detalleSec = 'No hay puntos de emisión configurados.'
      } else {
        const secsPorPunto = await Promise.all(
          puntos.map((p) => {
            const establecimientoId = p.establecimientoId
            return empresaApi.listarSecuenciales(empresaId, establecimientoId, p.id).catch(() => [])
          }),
        )
        const secs = secsPorPunto.flat()
        const secsProd = secs.filter(s => s.ambiente === 'PRODUCCION')
        hayProd = secsProd.length > 0
        detalleSec = hayProd
          ? `${secsProd.length} secuencial(es) PRODUCCION configurado(s)`
          : 'Ningún punto de emisión tiene secuencial en ambiente PRODUCCION.'
      }
    }
    const checkSec: PreflightCheck = {
      label: 'Secuencial PRODUCCION configurado',
      cumple: hayProd,
      detalle: detalleSec,
      accion: hayProd ? null : { label: 'Configurar secuencial', ruta: '/app/empresa/configuracion?tab=establecimientos' },
    }

    checks.value = [checkCert, checkPerfil, checkSec]
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudieron verificar los requisitos')
    dialogPreflight.value = false
  } finally {
    cargandoPreflight.value = false
  }
}

function irA(ruta: string) {
  dialogPreflight.value = false
  router.push(ruta)
}

async function aplicarCambio(nuevoAmbiente: 'PRUEBAS' | 'PRODUCCION') {
  if (!props.empresa) return
  cambiando.value = true
  error.value = null
  ok.value = null
  try {
    const e = await empresaApi.cambiarAmbienteDefault(props.empresa.id, nuevoAmbiente)
    ok.value = nuevoAmbiente === 'PRODUCCION'
      ? '¡Empresa en PRODUCCIÓN! Las facturas que emitas ahora son fiscales reales.'
      : 'Empresa de vuelta en PRUEBAS. Podés emitir facturas sin efectos fiscales.'
    emit('cambiado', e)
    void auth.fetchUser()
  } catch (e) {
    error.value = mensajeDeError(e, 'No se pudo cambiar el ambiente')
  } finally {
    cambiando.value = false
  }
}

function confirmarPasoAPro() {
  // Cierra el dialog de pre-flight y abre el ConfirmDialog final
  dialogPreflight.value = false
  confirm.require({
    message:
      'A partir de este cambio, todas las facturas que emitas tendrán EFECTOS FISCALES REALES ' +
      'frente al SRI: aparecerán en tu declaración, generarán obligación de IVA y de renta, ' +
      'y NO se pueden borrar (solo anular vía SRI). ¿Confirmás el cambio a PRODUCCIÓN?',
    header: '⚠ Cambiar a PRODUCCIÓN — efectos fiscales reales',
    icon: 'pi pi-exclamation-triangle',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, cambiar a PRODUCCIÓN',
    acceptClass: 'p-button-danger',
    accept: () => aplicarCambio('PRODUCCION'),
  })
}

function pedirVolverPruebas() {
  confirm.require({
    message:
      'Volver a PRUEBAS detiene la emisión fiscal real. Las facturas que YA emitiste en ' +
      'PRODUCCION siguen siendo válidas (no se afectan). Nuevas facturas serán en sandbox SRI ' +
      'sin efectos fiscales.',
    header: 'Volver a PRUEBAS',
    icon: 'pi pi-info-circle',
    rejectLabel: 'Cancelar',
    acceptLabel: 'Sí, volver a PRUEBAS',
    accept: () => aplicarCambio('PRUEBAS'),
  })
}
</script>

<template>
  <div class="ambiente-toggle">
    <ConfirmDialog />

    <div class="cabecera">
      <div>
        <h3>Ambiente SRI</h3>
        <p class="sub">Define si las facturas que emite esta empresa son de prueba o reales.</p>
      </div>
      <Tag :value="esProduccion ? 'PRODUCCIÓN' : 'PRUEBAS'"
           :severity="esProduccion ? 'danger' : 'info'"
           class="badge-actual" />
    </div>

    <Message v-if="ok" severity="success" :closable="true" @close="ok = null">{{ ok }}</Message>
    <Message v-if="error" severity="error" :closable="true" @close="error = null">{{ error }}</Message>

    <div class="explicacion">
      <div class="card-amb" :class="{ activo: !esProduccion }">
        <div class="card-head">
          <i class="pi pi-flask" />
          <h4>PRUEBAS</h4>
          <Tag v-if="!esProduccion" value="ACTUAL" severity="info" />
        </div>
        <ul>
          <li>Facturas <strong>sin efectos fiscales</strong> (sandbox SRI).</li>
          <li>No aparecen en declaraciones tributarias.</li>
          <li>Ideal para probar el flujo y entrenar al equipo.</li>
          <li>Endpoint: <code class="mono">celcer.sri.gob.ec</code></li>
        </ul>
        <Button v-if="esProduccion" label="Volver a PRUEBAS" icon="pi pi-arrow-left"
                severity="secondary" outlined
                :loading="cambiando" @click="pedirVolverPruebas" />
      </div>

      <div class="card-amb produccion" :class="{ activo: esProduccion }">
        <div class="card-head">
          <i class="pi pi-bolt" />
          <h4>PRODUCCIÓN</h4>
          <Tag v-if="esProduccion" value="ACTUAL" severity="danger" />
        </div>
        <ul>
          <li>Facturas <strong>fiscales reales</strong>.</li>
          <li>Aparecen en declaración IVA + renta.</li>
          <li>Una vez autorizadas, NO se pueden borrar (anulación vía SRI).</li>
          <li>Endpoint: <code class="mono">cel.sri.gob.ec</code></li>
        </ul>
        <Button v-if="!esProduccion" label="Cambiar a PRODUCCIÓN" icon="pi pi-bolt"
                severity="danger"
                :loading="cambiando" @click="correrPreflight" />
      </div>
    </div>

    <!-- Dialog pre-flight: muestra checks antes de confirmar -->
    <Dialog v-model:visible="dialogPreflight" modal :draggable="false"
            header="Antes de pasar a PRODUCCIÓN"
            :style="{ width: '560px' }" :breakpoints="{ '768px': '92vw' }">
      <p class="preflight-intro">
        Verificá que esta empresa cumple con los 3 requisitos del SRI para emitir facturas
        electrónicas reales.
      </p>

      <div v-if="cargandoPreflight" class="preflight-loading">
        <i class="pi pi-spin pi-spinner" /> Verificando requisitos…
      </div>

      <ul v-else class="checks">
        <li v-for="(c, i) in checks" :key="i" :class="{ cumple: c.cumple, falta: !c.cumple }">
          <i :class="['pi', c.cumple ? 'pi-check-circle' : 'pi-times-circle']" />
          <div class="check-body">
            <strong>{{ c.label }}</strong>
            <span class="detalle">{{ c.detalle }}</span>
            <Button v-if="c.accion" :label="c.accion.label" icon="pi pi-arrow-right"
                    size="small" severity="secondary" text
                    @click="irA(c.accion.ruta)" />
          </div>
        </li>
      </ul>

      <template #footer>
        <Button label="Cancelar" severity="secondary" text @click="dialogPreflight = false" />
        <Button label="Sí, todo listo — pasar a PRODUCCIÓN" icon="pi pi-bolt"
                severity="danger" :disabled="!todosCumplen || cargandoPreflight"
                @click="confirmarPasoAPro" />
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.ambiente-toggle { display: flex; flex-direction: column; gap: var(--sp-4); }
.cabecera { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--sp-3); flex-wrap: wrap; }
.cabecera h3 { margin: 0 0 var(--sp-1); font-family: var(--font-display); }
.sub { color: var(--color-muted); margin: 0; }
.badge-actual { font-weight: 700; padding: var(--sp-1) var(--sp-3); font-size: var(--fs-sm); }
.explicacion { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.card-amb { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--sp-4); display: flex; flex-direction: column; gap: var(--sp-3); }
.card-amb.activo { border-color: var(--color-action); border-width: 2px; }
.card-amb.produccion.activo { border-color: var(--color-danger); background: var(--color-danger-bg); }
.card-head { display: flex; align-items: center; gap: var(--sp-2); }
.card-head h4 { margin: 0; flex: 1; font-family: var(--font-display); }
.card-head i { font-size: var(--fs-lg); color: var(--color-muted); }
.card-amb.activo .card-head i { color: var(--color-action); }
.card-amb.produccion.activo .card-head i { color: var(--color-danger); }
ul { margin: 0; padding-left: var(--sp-4); color: var(--color-ink); font-size: var(--fs-sm); display: flex; flex-direction: column; gap: var(--sp-1); }
.mono { font-family: var(--font-mono); font-size: 0.9em; background: var(--color-marca-bg); padding: 2px 6px; border-radius: 4px; }

/* Pre-flight dialog */
.preflight-intro { color: var(--color-muted); margin: 0 0 var(--sp-4); }
.preflight-loading { padding: var(--sp-6); text-align: center; color: var(--color-muted); display: flex; align-items: center; justify-content: center; gap: var(--sp-2); }
.checks { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--sp-3); }
.checks li { display: flex; gap: var(--sp-3); padding: var(--sp-3); border-radius: var(--radius-md); border: 1px solid var(--color-border); }
.checks li.cumple { background: var(--color-success-bg); border-color: var(--color-success); }
.checks li.falta { background: var(--color-danger-bg); border-color: var(--color-danger); }
.checks li i { font-size: var(--fs-xl); flex-shrink: 0; }
.checks li.cumple i { color: var(--color-success); }
.checks li.falta i { color: var(--color-danger); }
.check-body { display: flex; flex-direction: column; gap: var(--sp-1); flex: 1; }
.check-body .detalle { color: var(--color-muted); font-size: var(--fs-sm); }
.check-body :deep(.p-button) { align-self: flex-start; padding: 0; }

@media (max-width: 768px) {
  .explicacion { grid-template-columns: 1fr; }
}
</style>
