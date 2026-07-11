# Estrategia de branching — CI vs CD

Este documento explica cómo las **ramas** del repositorio disparan diferentes partes del pipeline.

## Ramas oficiales

### Repo de código — `forseti-devsecops`

| Rama | Propósito | Qué se corre | Deploy |
|---|---|---|---|
| `main` | Código productivo | **CI + CD completo** — todos los workflows + `docker-build.yml` (Trivy + Syft + Cosign + push GHCR) + Jenkins release pipeline | Argo CD sync-ea a **`forseti-prod`** (sync manual como safeguard) |
| `dev` | Integración / staging | **Solo CI** — `ci.yml` + `codeql.yml` + `dependency-check.yml` + `gitleaks.yml`. **Sin** push a registry ni firma. | Argo CD sync-ea a **`forseti-staging`** automáticamente |
| `feature/*` | Trabajo en curso | **CI en el PR** — status checks obligatorios contra `dev` | Ningún deploy |
| `hotfix/*` | Emergencias en prod | Igual que `feature/*` pero PR directo a `main` | Después del merge, promoción manual |

### Repo GitOps — `forseti-devsecops-gitops`

Este repo NO tiene ramas por ambiente. Un solo `main` con **carpetas** por ambiente:

```
environments/
├── staging/values.yaml   ← Argo CD app "forseti-staging" watchea esta ruta
└── prod/values.yaml      ← Argo CD app "forseti-prod" watchea esta ruta
```

**¿Por qué carpetas y no ramas?** Porque con ramas por ambiente los conflicts al promover cambios de staging → prod son un dolor de cabeza. Con carpetas la promoción es un `sed` que Jenkins ejecuta.

## Flujo de trabajo

### Cambio nuevo (feature)

```
1. git checkout dev && git pull
2. git checkout -b feature/mi-cambio
3. codeo...
4. git commit + git push origin feature/mi-cambio
5. gh pr create --base dev
   → GitHub Actions corre CI (build + tests + CodeQL + Dep-Check + Gitleaks)
   → Status checks obligatorios (branch protection)
   → Reviewer aprueba
6. Merge PR → dev
   → docker-build.yml NO corre (dev no dispara CD)
   → Jenkins tampoco (solo main)
   → Pero un job separado dev-deploy actualiza el tag staging en el gitops repo
   → Argo CD staging sync-ea → nueva versión live en http://localhost:8000
```

### Release a producción

```
1. gh pr create --base main --head dev  (o directamente en la UI)
2. GitHub Actions corre CI completo
3. Después del merge:
   → docker-build.yml se dispara: Buildx + Trivy + Syft + Cosign keyless
   → Jenkins pipeline se dispara: Trivy gate + Cosign key + ZAP DAST + bump gitops prod
4. Aprobación manual en Argo CD para sync a forseti-prod (safeguard)
```

## Cómo lo garantiza el pipeline

**Los workflows YAML ya diferencian por rama:**

```yaml
# .github/workflows/ci.yml (CI en dev+main+PR)
on:
  push:
    branches: [main, dev]
  pull_request:
    branches: [main, dev]

# .github/workflows/docker-build.yml (CD SOLO en main)
on:
  push:
    branches: [main]
    tags: ['v*']
```

**El Jenkinsfile diferencia por rama:**

```groovy
stage('Promover al repo GitOps') {
  when { branch 'main' }   // ← solo main promueve a prod
  steps { ... }
}
```

## Branch protection (obligatorio en `main`)

- ✅ Requiere PR (no push directo)
- ✅ Requiere status checks verdes: `Backend — Java 21 + Maven`, `Frontend — Node 22 + Vue 3`, `CodeQL Analysis`, `OWASP Dep-Check`, `Gitleaks`
- ✅ Requiere 1 aprobación
- ✅ Prohíbe force-push
- ✅ Prohíbe borrar la rama

## Diagrama resumen

```
                                          feature/mi-cambio
                                                │
                                                │  git push
                                                ▼
                                          [PR contra dev]
                                                │
                                            CI corre
                                        (bloquea si rojo)
                                                │
                                             merge ▼
   ┌────────────────────────────┐    ┌────────────────────────┐
   │  dev  ──────────────────────► CI + auto-deploy staging   │
   │       (integración)          │  → gitops/environments/    │
   │                              │    staging/values.yaml    │
   │                              │  → Argo CD sync            │
   └─────────────┬────────────────┘    └────────────────────────┘
                 │  PR a main
                 ▼
   ┌────────────────────────────┐    ┌────────────────────────┐
   │  main ──────────────────────► CI + CD completo           │
   │       (producción)           │  + Trivy + Syft + Cosign   │
   │                              │  + push GHCR + Jenkins     │
   │                              │  + ZAP DAST                │
   │                              │  → gitops/environments/    │
   │                              │    prod/values.yaml        │
   │                              │  → Argo CD sync MANUAL     │
   └────────────────────────────┘    └────────────────────────┘
```
