---
marp: true
theme: default
size: 16:9
paginate: true
style: |
  section {
    background: #fff;
    color: #1E293B;
    font-family: 'Segoe UI', 'Inter', sans-serif;
    padding: 40px 60px 30px 60px;
    font-size: 20pt;
    line-height: 1.35;
  }
  h1, h2 {
    color: #1E3A8A;
    font-family: Georgia, 'Lora', serif;
    margin: 0 0 12px 0;
  }
  h1 { font-size: 38pt; border-bottom: 3px solid #FB923C; padding-bottom: 6px; }
  h2 { font-size: 28pt; border-bottom: 2px solid #FB923C; padding-bottom: 4px; }
  h3 { font-size: 22pt; color: #FB923C; margin: 8px 0 4px 0; }
  strong { color: #1E3A8A; }
  em { color: #FB923C; font-style: normal; font-weight: 600; }
  ul, ol { margin: 4px 0 8px 0; padding-left: 26px; }
  li { margin-bottom: 3px; }
  p { margin: 6px 0; }
  code {
    font-family: 'Consolas', 'JetBrains Mono', monospace;
    font-size: 0.88em;
    background: #f6f8fa;
    padding: 1px 6px;
    border-radius: 3px;
  }
  pre {
    font-family: 'Consolas', monospace;
    font-size: 13pt;
    background: #f6f8fa;
    border-left: 4px solid #FB923C;
    padding: 12px 18px;
    line-height: 1.35;
    margin: 8px 0;
    overflow: hidden;
  }
  pre code { background: none; padding: 0; }
  table {
    font-size: 18pt;
    border-collapse: collapse;
    margin: 8px 0;
    width: 100%;
  }
  th, td { padding: 7px 12px; border: 1px solid #ddd; text-align: left; }
  th { background: #EEF2FF; color: #1E3A8A; font-weight: 700; }
  img { border: 1px solid #ddd; border-radius: 6px; box-shadow: 0 2px 12px rgba(0,0,0,0.12); display: block; margin: 8px auto; }
  .cite { font-size: 14pt; color: #666; font-style: italic; text-align: center; margin-top: 4px; }
  section.title h1 { border: none; text-align: center; }
  section.title { text-align: center; }
  .cierre {
    text-align: center;
    margin-top: 20px;
    padding: 18px;
    background-color: #FFF7ED;
    border: 3px solid #FB923C;
    border-radius: 10px;
  }
  .cierre h1 { border: none; font-size: 32pt; color: #1E3A8A; margin: 8px 0 0 0; }
  .cierre p { margin: 4px 0; font-size: 20pt; }
---

<!-- Se convierte a PDF/PPTX con:
  npx @marp-team/marp-cli docs/presentacion.md --allow-local-files -o docs/presentacion.pdf
  npx @marp-team/marp-cli docs/presentacion.md --allow-local-files -o docs/presentacion.pptx
-->

<!-- _paginate: false -->

<div style="text-align:center; margin-top: 60px;">

# CI/CD con enfoque **DevSecOps**

## Caso Forseti — Facturación electrónica SRI Ecuador

<br>

### **Hernán Jurado** · **Sebastián Cruz**

Ingeniería en Software · UDLA · Facultad de Ingeniería y Ciencias Aplicadas
ISWZ3205 · Procesos de Software · Julio 2026

<br>

github.com/TARIUS-S-A-S/forseti-devsecops
</div>

---

## 1 · El problema

**Forseti** es un SaaS de facturación electrónica **en producción real** que emite comprobantes al SRI Ecuador firmados con XAdES-BES.

### Tres riesgos combinados

- **Compliance LOPDP** — procesa RUCs, direcciones, correos: datos personales bajo la Ley Orgánica de Protección de Datos del Ecuador.
- **Certificados digitales** — maneja `.p12` privados cifrados con AES-256-GCM. Un fallo de firma corrompe la validez fiscal del documento.
- **Cadencia alta** — la base tributaria SRI cambia 2 veces al año. No podemos frenar los releases mientras arreglamos seguridad.

### Pregunta guía

**¿Cómo entregamos rápido sin aflojar la seguridad?**

Respuesta → un pipeline con **DevSecOps integrado en cada etapa** — no al final del ciclo.

---

## 2 · Arquitectura en 3 capas

```
    Developer                                                         
        │ git push                                                    
        ▼                                                             
┌─────────────────────────┐   ┌─────────────────────────┐   ┌────────────────────────┐
│ 1) GitHub Actions       │   │ 2) Jenkins              │   │ 3) K8s + GitOps        │
│  Build + Test           │   │  Docker Build           │   │  Argo CD sync          │
│  SAST (CodeQL)          │──▶│  Trivy Gate CRITICAL   │──▶│  Kyverno 5 policies    │
│  SCA (Dep-Check)        │   │  Syft SBOM              │   │  Ingress + PVC         │
│  Secrets (Gitleaks)     │   │  Cosign firma           │   │  ZAP DAST (staging)    │
│                         │   │  Push GHCR              │   │                        │
│  ≤ 5 min feedback PR    │   │  Bump gitops repo       │   │  Cluster kind 3 nodos  │
└─────────────────────────┘   └─────────────────────────┘   └────────────────────────┘
```

**Regla de oro:** cada herramienta hace lo que hace mejor. **Nada se pisa.**

---

## 3 · Matriz de herramientas — sin redundancia

<style scoped>
table { font-size: 13pt; }
th, td { padding: 3px 8px; }
</style>

| # | Etapa | Herramienta | Rol único (que las demás NO cubren) |
|---|---|---|---|
| 1 | CI orquestador | **GitHub Actions** | Feedback en el PR (≤ 5 min) |
| 2 | **SAST** | **CodeQL** | Bugs de código Java + TS sin ejecutar |
| 3 | SCA (deps) | **OWASP Dep-Check** | CVEs en `pom.xml` + `package-lock` |
| 4 | Secrets | **Gitleaks** | Credenciales en historial Git |
| 5 | CD orquestador | **Jenkins** | Release engineering con gates |
| 6 | SCA (imagen) | **Trivy** | CVEs en OS + libs de la imagen |
| 7 | SBOM | **Syft** | Inventario SPDX de cada imagen |
| 8 | Signing | **Cosign** (Sigstore) | Firma keyless + key-based |
| 9 | GitOps CD | **Argo CD** | Sync declarativo del cluster |
| 10 | Policy | **Kyverno** | Admission control YAML-nativo |
| 11 | **DAST** | **OWASP ZAP** | Vulns en runtime del deploy |

**11 herramientas, 11 responsabilidades distintas.** Descartadas: GitLab CI, Snyk, Notary v2, OPA/Gatekeeper, FluxCD.

---

## 4 · CI en GitHub Actions — evidencia real

**5 workflows en paralelo por PR** (feedback ≤ 5 min): **SAST CodeQL** · **SCA Dep-Check** · **Secrets Gitleaks** · CI Build+Test · CD por rama. **Branch protection** en `main` obliga 5 status checks verdes.

![h:400](./capturas/img-07-github-pr.png)

<div class="cite">PR #1 real — status checks del pipeline visibles en el sidebar</div>

---

## 5 · Jenkins — Stage View del pipeline

**9 stages:** Checkout · Build imágenes Docker (paralelo BE/FE) · **Trivy Scan** · **Trivy Gate CRITICAL** ⛔ · **Syft SBOM** · Push GHCR · **Cosign firma + attest** · **ZAP DAST** · Bump GitOps.

![h:400](./capturas/img-04-jenkins-job.png)

<div class="cite">Jenkins corre en Docker con Trivy · Syft · Cosign · kubectl · Helm preinstalados</div>

---

## 6 · Trivy en acción — resultados reales

Scan real contra `forseti-backend:local` con **Trivy 0.72.0** — **7 CRITICAL + 8 HIGH** en libs Spring/Thymeleaf. Destacados: `CVE-2026-40477` Thymeleaf **SSTI** · `CVE-2026-22732` Spring Security **bypass** · `CVE-2026-40973` Spring Boot **RCE**.

![h:400](./capturas/img-02-trivy-report.png)

<div class="cite">En Jenkins <code>trivy image --severity CRITICAL --exit-code 1</code> <strong>rompe el build</strong> si hay CVE fixable</div>

---

## 7 · GitOps con Argo CD — el cluster refleja Git

**Regla:** el cluster NO acepta cambios directos — todo viene del repo `forseti-devsecops-gitops`. Argo CD **multi-source** combina chart Helm + values.yaml. Auditoría automática · Rollback = `git revert`. App **forseti-staging = Synced + Healthy ✅**.

![h:380](./capturas/img-06-argocd-apps-detail.png)

<div class="cite">Tree gráfico — Application → Namespace → Deployments → Pods + PVC + Service + Ingress</div>

---

## 8 · Kyverno — última línea de defensa

### 5 policies enforce activas en el cluster

| Policy | Rechaza si… |
|---|---|
| `verify-forseti-image-signatures` | Imagen sin firma Cosign válida |
| `require-resource-limits` | Container sin `requests` o `limits` |
| `disallow-latest-tag` | Tag `:latest` en `forseti-prod` |
| `disallow-privileged-containers` | `privileged=true`, hostNetwork/PID/IPC |
| `require-run-as-non-root` | `runAsNonRoot` no está en `true` |

### Prueba en vivo — Kyverno bloqueando 3 policies al mismo tiempo

```
$ kubectl -n forseti-prod run test-latest --image=nginx:latest --dry-run=server

Error from server: admission webhook "validate.kyverno.svc-fail" denied the request:
  disallow-latest-tag/block-latest: 'En forseti-prod está prohibido el tag :latest'
  require-resource-limits/validate-limits: 'container debe declarar requests + limits'
  require-run-as-non-root/check-runasnonroot: 'runAsNonRoot debe ser true'
```

---

<!-- _paginate: false -->

<!-- _paginate: false -->

## 9 · Resultados y cierre

**🔍 Hallazgos de las 7 pruebas:** CodeQL 0 críticos · **Trivy 7 CRITICAL + 8 HIGH** en Spring/Thymeleaf · Dep-Check 3 MEDIUM · **ZAP 59 PASS · 0 FAIL** · Gitleaks 0 secretos · Cosign 100% firmadas · Kyverno 3 bloqueos en vivo.

**📊 Métricas:** commit → staging **≈22 min** · PR **≤5 min** · **0** CVEs CRIT sin mitigar.

**✅ Aprendizajes:** separar **CI rápido (GH Actions)** de **CD pesado (Jenkins)** funcionó · **Cosign + Kyverno** hacen la firma real · **kind reproducible en 15 min**.

**🔧 Mejoras:** Renovate/Dependabot · Falco runtime · Sealed-Secrets · Argo Rollouts · bump Spring 6.4.6 + Thymeleaf 3.1.4.

<div class="cierre">

**📦 github.com/TARIUS-S-A-S/forseti-devsecops** (MIT)

# Gracias — ¿Preguntas?

</div>
