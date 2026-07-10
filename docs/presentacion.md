---
marp: true
theme: default
size: 16:9
paginate: true
header: 'CI/CD con enfoque DevSecOps · ISWZ3205'
footer: 'Hernán Jurado · 2026-07-17'
style: |
  section {
    background: #fff;
    color: #1E293B;
    font-family: 'Inter', sans-serif;
  }
  h1, h2 {
    color: #1E3A8A;
    font-family: 'Lora', serif;
  }
  .accent {
    color: #FB923C;
    font-weight: 700;
  }
  code, pre {
    font-family: 'JetBrains Mono', monospace;
    font-size: 0.85em;
  }
  table { font-size: 0.72em; }
---

<!-- Presentación pensada para 10 láminas × ~1min 15s cada una = 12 min. -->
<!-- Se convierte a PDF con: marp docs/presentacion.md -o docs/presentacion.pdf -->

# CI/CD con enfoque **DevSecOps**

## Caso de estudio: **Forseti**

Facturación electrónica SRI (Ecuador) → pipeline completo del commit al cluster Kubernetes.

<br>

**Hernán Jurado** — Ingeniería en Software
UDLA · ISWZ3205 Procesos de Software
Julio 2026

---

## Lámina 2 · El problema

**Forseti** procesa datos fiscales sensibles (RUCs, comprobantes electrónicos firmados con XAdES-BES).

Riesgos concretos:

- **Compliance**: LOPDP Ecuador (2021) + reglamento SRI.
- **Cadena de suministro**: una lib npm/Maven comprometida = firmas SRI inválidas.
- **Secretos**: certificados `.p12` + master key AES-256-GCM en el flujo.

**Pregunta guía:** ¿cómo entregamos rápido *sin* aflojar la seguridad?

<span class="accent">→ Un pipeline CI/CD con enfoque DevSecOps.</span>

---

## Lámina 3 · Arquitectura en 3 capas

<div style="font-size:0.72em">

```
┌────────────────────────┐   ┌──────────────────────┐   ┌────────────────────────┐
│ 1. GITHUB ACTIONS       │   │ 2. JENKINS            │   │ 3. GITOPS + K8S        │
│  ─ Build + tests        │   │  ─ Build imagen       │   │  ─ Argo CD sync        │
│  ─ CodeQL (SAST)        │─▶│  ─ Trivy gate         │─▶│  ─ Helm chart          │
│  ─ OWASP Dep-Check      │   │  ─ Syft SBOM          │   │  ─ Kyverno 5 policies  │
│  ─ Gitleaks             │   │  ─ Cosign sign+attest │   │  ─ ZAP baseline (DAST) │
│  ≤ 5 min feedback PR    │   │  ─ Push gitops repo   │   │  ─ kind cluster local  │
└────────────────────────┘   └──────────────────────┘   └────────────────────────┘
```

</div>

**Regla de oro:** cada herramienta tiene un rol único. **Nada se pisa.**

---

## Lámina 4 · Herramientas — matriz sin redundancia

| # | Etapa | Herramienta | Qué cubre (que las demás NO cubren) |
|---|---|---|---|
| 1 | CI orquestador | **GitHub Actions** | Feedback en el PR mismo |
| 2 | SAST | **CodeQL** | Bugs en código propio (Java + TS) |
| 3 | SCA (deps) | **OWASP Dep-Check** | CVEs de libs Maven/npm |
| 4 | Secretos | **Gitleaks** | Credenciales en historial Git |
| 5 | CD pesado | **Jenkins** | Release pipeline con etapas largas |
| 6 | Container scan | **Trivy** | CVEs en OS + libs de la imagen |
| 7 | SBOM | **Syft** | Inventario SPDX-JSON |
| 8 | Signing | **Cosign** | Firma keyless (Sigstore) |
| 9 | GitOps | **Argo CD** | Sync declarativo del cluster |
| 10 | Policy | **Kyverno** | Admission control YAML-nativo |
| 11 | DAST | **OWASP ZAP** | Vulns en runtime del deploy |

**11 herramientas, 11 responsabilidades distintas.**

---

## Lámina 5 · GitHub Actions — CI que **no bloquea**

```yaml
jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: mvn -B verify   # incluye ArchUnit + JUnit
```

5 workflows en paralelo:

- ✅ `ci.yml` — build + test
- ✅ `codeql.yml` — SAST
- ✅ `dependency-check.yml` — SCA
- ✅ `gitleaks.yml` — secretos
- ✅ `docker-build.yml` — Buildx + Trivy + Syft + Cosign keyless (solo `main`)

<span class="accent">Corre en 5 min → developer ve el error mientras el contexto está fresco.</span>

---

## Lámina 6 · Jenkins — release engineering **con gates**

Pipeline declarativo (`Jenkinsfile`) con 9 stages.

```groovy
stage('Trivy — Gate CRITICAL') {
  steps {
    sh 'trivy image --severity CRITICAL --exit-code 1 --ignore-unfixed ${IMG}'
    // ↑ si hay CVE CRITICAL fixable, rompe el build
  }
}

stage('Cosign — firma + attest SBOM') {
  steps {
    sh 'cosign sign --key $KEY   ${IMG}'
    sh 'cosign attest --predicate sbom.spdx.json --type spdxjson ${IMG}'
  }
}
```

**Diferencia clave con GH Actions:** llave propia (no keyless) + gate estricto + DAST + push a repo GitOps.

---

## Lámina 7 · GitOps con Argo CD — el cluster es lo que dice Git

**Regla:** el cluster **no acepta** cambios directos. Todo viene de un commit al repo `forseti-devsecops-gitops`.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata: { name: forseti-staging }
spec:
  source:
    repoURL: https://github.com/TARIUS-S-A-S/forseti-devsecops-gitops
    path: environments/staging
    helm: { valueFiles: [values.yaml] }
  syncPolicy:
    automated: { prune: true, selfHeal: true }
```

**Beneficios:**
- Auditoría automática (git log = historia de deploys)
- Rollback = `git revert`
- Cero `kubectl apply` manuales

---

## Lámina 8 · Kyverno — la última línea de defensa

5 policies en modo **Enforce** en el cluster:

| Policy | Rechaza si… |
|---|---|
| `verify-forseti-image-signatures` | Imagen sin firma Cosign válida |
| `require-resource-limits` | Container sin `requests` o `limits` |
| `disallow-latest-tag` | Tag `:latest` en `forseti-prod` |
| `disallow-privileged-containers` | `privileged=true`, hostNetwork, hostPID, hostIPC |
| `require-run-as-non-root` | `runAsNonRoot` no está en `true` |

**Ejemplo de bloqueo real:**

```bash
$ kubectl -n forseti-prod run test --image=nginx:latest
Error: policy disallow-latest-tag/block-latest: en forseti-prod está prohibido
el tag :latest o imagen sin tag. Usá sha-… o vX.Y.Z.
```

---

## Lámina 9 · DevSecOps en números — evidencias

| Métrica | Resultado |
|---|---|
| Tiempo commit → pod running en staging | **≈ 22 min** |
| Tiempo hasta feedback en PR | **≤ 5 min** |
| Herramientas de seguridad por release | **7** |
| CVEs CRITICAL fixables no mitigados | **0** |
| Secretos en historial | **0** |
| Pods sin firma Cosign en cluster | **0** (Kyverno los rechaza) |
| CodeQL alerts open | **0** |

**Defensa en profundidad:** ninguna herramienta hace redundante a las demás.
SAST atrapa lo del código, SCA lo de las deps, DAST lo del runtime, signing lo del supply chain, policy lo del cluster.

---

## Lámina 10 · Aprendizajes + próximos pasos

**Lo que funcionó:**

- Separar CI rápido (Actions) de CD pesado (Jenkins) — cada uno hace lo que hace mejor
- GitOps + Kyverno — la firma Cosign deja de ser decorativa
- Cosign keyless (OIDC) — supply chain security *sin* HSM

**Lo que quedó pendiente:**

1. Renovate/Dependabot autonómo con auto-merge por tests
2. Runtime security con Falco (complementa DAST post-mortem)
3. Sealed-Secrets / External-Secrets-Operator (hoy son placeholders)
4. Argo Rollouts para canaries y blue-green

<br>

<span class="accent">Repo: https://github.com/TARIUS-S-A-S/forseti-devsecops</span>
Gracias · ¿Preguntas?
