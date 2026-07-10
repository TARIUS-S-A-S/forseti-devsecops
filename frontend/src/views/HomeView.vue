<script setup lang="ts">
import ForsetiLogo from '@/components/ForsetiLogo.vue'
import EndorsementTarius from '@/components/EndorsementTarius.vue'

const features = [
  {
    icon: 'pi-file-edit',
    title: 'Facturación electrónica SRI',
    desc: 'Emisión directa al SRI con firma XAdES-BES. Sin intermediarios pagados, sin facturadores externos.',
    color: 'var(--color-marca)',
  },
  {
    icon: 'pi-shield',
    title: 'Cumplimiento sin estrés',
    desc: 'Declaraciones, anexos (ATS, APS) y estados financieros para SuperCIA — listos para subir al portal.',
    color: 'var(--color-action)',
  },
  {
    icon: 'pi-calendar',
    title: 'Calendario tributario',
    desc: 'Alertas 30/15/5 días antes de cada obligación. Nunca más una multa por olvido.',
    color: 'var(--color-warning)',
  },
  {
    icon: 'pi-users',
    title: 'Multi-empresa',
    desc: 'Una sola cuenta, varias empresas. RIMPE, general, S.A. o persona natural — cualquier régimen.',
    color: 'var(--color-success)',
  },
  {
    icon: 'pi-chart-line',
    title: 'Dashboard de dirección',
    desc: 'Ingresos, egresos, IVA por cobrar, deudas pendientes — todo en una vista única.',
    color: 'var(--color-marca-deep)',
  },
  {
    icon: 'pi-lock',
    title: 'Seguridad por diseño',
    desc: 'RLS multi-tenant, 2FA, Argon2id, audit log. Tus datos viven aislados y cifrados.',
    color: '#0F2A43',
  },
]

const stats = [
  { value: '~3 min', label: 'tiempo promedio para emitir factura' },
  { value: '100%', label: 'cobertura del catálogo SRI + SuperCIA' },
  { value: '7 años', label: 'archivo legal automático de XMLs' },
]
</script>

<template>
  <div class="home">
    <!-- ─── Top nav ─── -->
    <header class="top">
      <div class="container top-inner">
        <RouterLink to="/" class="brand">
          <ForsetiLogo variant="ink" :size="36" />
          <span class="forseti-wordmark brand-text">Forseti</span>
        </RouterLink>

        <nav class="nav">
          <a href="#features">Producto</a>
          <a href="#stats">Por qué</a>
          <RouterLink to="/login" class="btn-primary nav-cta">
            Entrar <i class="pi pi-arrow-right" />
          </RouterLink>
        </nav>
      </div>
    </header>

    <!-- ─── Hero ─── -->
    <section class="hero">
      <div class="container hero-inner">
        <div class="hero-content">
          <p class="hero-kicker">Facturación electrónica · Cumplimiento · Gestión</p>
          <h1 class="hero-title">
            Tu empresa cumple con el SRI <br />
            <span class="hero-title-accent">sin perder un sábado</span>
          </h1>
          <p class="hero-sub">
            Forseti emite facturas electrónicas, prepara declaraciones, genera anexos y
            estados financieros para la SuperCIA. Todo en un mismo lugar, todo en marcha cuando vos no estás.
          </p>
          <div class="hero-cta">
            <RouterLink to="/login" class="btn-primary btn-lg">
              Comenzar ahora <i class="pi pi-arrow-right" />
            </RouterLink>
            <a href="#features" class="btn-ghost btn-lg">
              Ver qué hace
            </a>
          </div>
          <div class="hero-meta">
            <i class="pi pi-shield" /> Multi-empresa &nbsp;·&nbsp;
            <i class="pi pi-check-circle" /> Para cualquier régimen tributario &nbsp;·&nbsp;
            <i class="pi pi-bolt" /> Sin intermediarios
          </div>
        </div>

        <div class="hero-visual">
          <div class="visual-card">
            <div class="visual-header">
              <span class="dot dot-red" />
              <span class="dot dot-yellow" />
              <span class="dot dot-green" />
              <span class="visual-title">factura-001-001-000000247.xml</span>
            </div>
            <div class="visual-body">
              <div class="visual-row">
                <span class="visual-label">Cliente</span>
                <span class="visual-value">NikoTool</span>
              </div>
              <div class="visual-row">
                <span class="visual-label">Concepto</span>
                <span class="visual-value">Desarrollo de sitio web</span>
              </div>
              <div class="visual-row">
                <span class="visual-label">Subtotal</span>
                <span class="visual-value tabular">$ 250.00</span>
              </div>
              <div class="visual-row">
                <span class="visual-label">IVA 15%</span>
                <span class="visual-value tabular">$ 37.50</span>
              </div>
              <div class="visual-row visual-row-total">
                <span class="visual-label">Total</span>
                <span class="visual-value tabular">$ 287.50</span>
              </div>
              <div class="visual-status">
                <i class="pi pi-check-circle" /> Autorizada por el SRI · 2 s
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ─── Stats ─── -->
    <section id="stats" class="stats">
      <div class="container stats-grid">
        <div v-for="s in stats" :key="s.label" class="stat-card">
          <div class="stat-value">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </div>
      </div>
    </section>

    <!-- ─── Features ─── -->
    <section id="features" class="features">
      <div class="container">
        <div class="features-head">
          <p class="section-kicker">Todo en una sola app</p>
          <h2 class="section-title">Una herramienta para cumplir, no una más para configurar</h2>
        </div>

        <div class="features-grid">
          <div
            v-for="f in features"
            :key="f.title"
            class="feature-card"
          >
            <div class="feature-icon" :style="{ background: f.color }">
              <i :class="['pi', f.icon]" />
            </div>
            <h3 class="feature-title">{{ f.title }}</h3>
            <p class="feature-desc">{{ f.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ─── CTA final ─── -->
    <section class="cta">
      <div class="container cta-inner">
        <h2 class="cta-title">¿Listo para dejar de pelearte con la facturación?</h2>
        <p class="cta-sub">
          Forseti está en fase piloto. Si querés probarlo gratis durante el piloto, hablanos.
        </p>
        <RouterLink to="/login" class="btn-marca btn-lg">
          Comenzar ahora <i class="pi pi-arrow-right" />
        </RouterLink>
      </div>
    </section>

    <!-- ─── Footer ─── -->
    <footer class="footer">
      <div class="container footer-inner">
        <div class="footer-left">
          <RouterLink to="/" class="brand">
            <ForsetiLogo variant="ink" :size="28" />
            <span class="forseti-wordmark">Forseti</span>
          </RouterLink>
          <EndorsementTarius class="footer-endorsement" />
        </div>
        <div class="footer-right">
          <span class="footer-meta">© 2026 TARIUS S.A.S — Quito, Ecuador</span>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.home {
  min-height: 100vh;
  background: var(--color-bg);
}

/* ─── Top nav ─── */
.top {
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  position: sticky;
  top: 0;
  z-index: 50;
  backdrop-filter: blur(8px);
}
.top-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--sp-4);
  padding-bottom: var(--sp-4);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  color: var(--color-ink);
}
.brand-text {
  font-size: var(--fs-xl);
}
.nav {
  display: flex;
  align-items: center;
  gap: var(--sp-6);
}
.nav a {
  color: var(--color-muted);
  font-weight: 500;
}
.nav a:hover { color: var(--color-ink); }
.nav-cta {
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--fs-sm);
}

/* ─── Hero ─── */
.hero {
  padding: var(--sp-16) 0;
  background:
    radial-gradient(ellipse at top right, var(--color-marca-bg) 0%, transparent 50%),
    var(--color-bg);
  overflow: hidden;
}
.hero-inner {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: var(--sp-12);
  align-items: center;
}
.hero-kicker {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--color-marca-deep);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  margin-bottom: var(--sp-4);
}
.hero-title {
  font-size: clamp(var(--fs-xl), 6vw, var(--fs-3xl));
  line-height: 1.1;
  letter-spacing: -0.03em;
  margin-bottom: var(--sp-6);
}
.hero-title-accent {
  color: var(--color-marca);
  font-style: italic;
}
.hero-sub {
  font-size: var(--fs-lg);
  color: var(--color-muted);
  line-height: 1.5;
  margin-bottom: var(--sp-8);
  max-width: 520px;
}
.hero-cta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
  margin-bottom: var(--sp-6);
}
.btn-lg {
  padding: var(--sp-4) var(--sp-8);
  font-size: var(--fs-md);
}
.hero-meta {
  font-size: var(--fs-sm);
  color: var(--color-muted);
}
.hero-meta i {
  color: var(--color-action);
  margin-right: var(--sp-1);
}

/* ─── Visual card (factura mock) ─── */
.hero-visual {
  display: flex;
  justify-content: center;
}
.visual-card {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-lg);
  width: 100%;
  max-width: 480px;
  overflow: hidden;
  transform: rotate(-1deg);
  transition: transform 0.4s var(--ease-out);
}
.visual-card:hover { transform: rotate(0); }

.visual-header {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4);
  background: #F1F5F9;
  border-bottom: 1px solid var(--color-border);
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.dot-red { background: #EF4444; }
.dot-yellow { background: #F59E0B; }
.dot-green { background: #10B981; }
.visual-title {
  margin-left: var(--sp-2);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--color-muted);
}

.visual-body {
  padding: var(--sp-6);
}
.visual-row {
  display: flex;
  justify-content: space-between;
  padding: var(--sp-3) 0;
  border-bottom: 1px dashed var(--color-border);
}
.visual-row-total {
  border-bottom: none;
  border-top: 2px solid var(--color-ink);
  padding-top: var(--sp-4);
  margin-top: var(--sp-3);
  font-weight: 700;
  font-size: var(--fs-lg);
}
.visual-label {
  color: var(--color-muted);
  font-size: var(--fs-sm);
}
.visual-value {
  color: var(--color-ink);
  font-weight: 500;
}
.visual-status {
  margin-top: var(--sp-6);
  padding: var(--sp-3) var(--sp-4);
  background: rgba(22, 163, 74, 0.08);
  color: var(--color-success);
  border-radius: var(--radius-md);
  font-weight: 500;
  font-size: var(--fs-sm);
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

/* ─── Stats ─── */
.stats {
  padding: var(--sp-12) 0;
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
}
.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-8);
}
.stat-card {
  text-align: center;
}
.stat-value {
  font-family: var(--font-display);
  font-size: var(--fs-2xl);
  font-weight: 700;
  color: var(--color-marca);
  margin-bottom: var(--sp-2);
}
.stat-label {
  color: var(--color-muted);
  font-size: var(--fs-md);
}

/* ─── Features ─── */
.features {
  padding: var(--sp-16) 0;
}
.features-head {
  text-align: center;
  margin-bottom: var(--sp-12);
}
.section-kicker {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--color-marca-deep);
  letter-spacing: 0.05em;
  text-transform: uppercase;
  margin-bottom: var(--sp-3);
}
.section-title {
  font-size: clamp(var(--fs-xl), 4vw, var(--fs-2xl));
  letter-spacing: -0.02em;
  max-width: 700px;
  margin: 0 auto;
}
.features-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: var(--sp-6);
}
.feature-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--sp-8);
  transition: transform 0.2s var(--ease-out), box-shadow 0.2s var(--ease-out);
}
.feature-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-md);
}
.feature-icon {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 22px;
  margin-bottom: var(--sp-4);
}
.feature-title {
  font-size: var(--fs-lg);
  font-family: var(--font-display);
  margin-bottom: var(--sp-3);
}
.feature-desc {
  color: var(--color-muted);
  line-height: 1.6;
}

/* ─── CTA final ─── */
.cta {
  padding: var(--sp-16) 0;
  background:
    linear-gradient(135deg, var(--color-marca) 0%, var(--color-marca-deep) 100%);
  color: white;
  text-align: center;
}
.cta-title {
  color: white;
  font-size: clamp(var(--fs-xl), 5vw, var(--fs-2xl));
  letter-spacing: -0.02em;
  margin-bottom: var(--sp-4);
}
.cta-sub {
  color: rgba(255, 255, 255, 0.85);
  font-size: var(--fs-lg);
  margin-bottom: var(--sp-8);
  max-width: 600px;
  margin-left: auto;
  margin-right: auto;
}
.cta .btn-marca {
  background: white;
  color: var(--color-marca-deep);
}
.cta .btn-marca:hover {
  background: var(--color-marca-soft);
}

/* ─── Footer ─── */
.footer {
  padding: var(--sp-8) 0;
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
}
.footer-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-4);
}
.footer-left {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.footer-endorsement {
  margin-left: 38px;
}
.footer-meta {
  color: var(--color-muted);
  font-size: var(--fs-sm);
}

/* ─── Responsive ─── */
@media (max-width: 900px) {
  .hero-inner {
    grid-template-columns: 1fr;
  }
  .stats-grid {
    grid-template-columns: 1fr;
    gap: var(--sp-4);
  }
  .nav a:not(.nav-cta) { display: none; }
  .hero-cta { flex-direction: column; align-items: stretch; }
}
</style>
