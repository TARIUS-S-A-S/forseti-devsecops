# Forseti DevSecOps — Pipeline CI/CD con enfoque DevSecOps

> Proyecto académico — **ISWZ3205 Procesos de Software** — Universidad de las Américas (UDLA), Facultad de Ingeniería y Ciencias Aplicadas.
> Autor: **Hernán Jurado** — TARIUS S.A.S.
> Fecha entrega: **2026-07-17**.

---

## 1. ¿Qué es Forseti?

**Forseti** es un SaaS de facturación electrónica para Ecuador — emite facturas / notas de crédito firmadas con XAdES-BES y las envía al SRI. Este repositorio es la **versión demo pública** del código real (versión productiva vive en repos privados de `TARIUS-S-A-S`).

Sirve como caso de estudio para implementar **un pipeline CI/CD con enfoque DevSecOps** completo: build, tests, análisis de seguridad (SAST + DAST + SCA + secrets), firma de artefactos, despliegue GitOps a un cluster Kubernetes y policies de admisión.

## 2. Arquitectura del pipeline

```
   Developer                                                            
   ────────                                                             
      │ git push                                                        
      ▼                                                                 
┌─────────────────────────────────────────────────────────────────────┐ 
│ GitHub — forseti-devsecops (repo código)                            │ 
└──────────────────────────┬──────────────────────────────────────────┘ 
                           │                                            
       ┌───────────────────┼──────────────────────────┐                
       ▼                   ▼                          ▼                
┌────────────────┐  ┌──────────────┐        ┌────────────────────┐    
│ GitHub Actions │  │  CodeQL SAST │        │  Gitleaks secrets  │    
│ Build + Test   │  │  (Java + TS) │        │  OWASP Dep-Check   │    
│ Feedback en PR │  └──────────────┘        └────────────────────┘    
└───────┬────────┘                                                    
        │ merge a main                                                
        ▼                                                              
┌─────────────────────────────────────────────────────────────────────┐
│  JENKINS  (pipeline pesado en main)                                 │
│  1. docker build backend + frontend                                 │
│  2. Trivy scan CVEs (imagen)  →  Gate CRITICAL bloquea              │
│  3. Syft SBOM SPDX                                                  │
│  4. Push GHCR                                                       │
│  5. Cosign sign + attest SBOM  →  Cosign verify                     │
│  6. OWASP ZAP baseline contra staging (DAST)                        │
│  7. Bump tag en repo GitOps                                         │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ git push a forseti-devsecops-gitops      
                           ▼                                          
┌─────────────────────────────────────────────────────────────────────┐
│ ARGO CD (en cluster kind local)                                     │
│  ─ Watchea el repo GitOps                                           │
│  ─ Sync automático a namespace forseti-staging                      │
│  ─ Sync manual a forseti-prod                                       │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ kubectl apply                            
                           ▼                                          
┌─────────────────────────────────────────────────────────────────────┐
│ CLUSTER KUBERNETES (kind — 1 control-plane + 2 workers)             │
│                                                                     │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐   ┌────────────────┐  │
│  │  backend  │  │ frontend  │  │ postgres  │   │  ingress-nginx │  │
│  └───────────┘  └───────────┘  └───────────┘   └────────────────┘  │
│                                                                     │
│  ─ KYVERNO: verifica firma Cosign, exige limits, prohíbe :latest,   │
│    prohíbe privileged, exige non-root                               │
└─────────────────────────────────────────────────────────────────────┘
```

## 3. Matriz de herramientas (sin repetir funcionalidad)

| # | Herramienta | Categoría | Qué hace acá | Alternativa descartada |
|---|---|---|---|---|
| 1 | **GitHub Actions** | CI rápido | Build + test unitario + lint (feedback PR ≤ 5min) | GitLab CI (no aplica), CircleCI (paid) |
| 2 | **CodeQL** | SAST | Análisis estático Java + TypeScript en cada PR | SonarCloud (nativo GitHub, más simple) |
| 3 | **OWASP Dependency-Check** | SCA (deps) | CVEs en deps Maven + npm | Snyk (paid tier) |
| 4 | **Gitleaks** | Secrets | Impide commits con secretos | TruffleHog (equivalente) |
| 5 | **Jenkins** | CD pesado | Orquesta build de imagen + firma + DAST + GitOps | Tekton (menos maduro para demo) |
| 6 | **Docker Buildx** | Build | Multi-stage builds reproducibles | Buildpacks (menos control) |
| 7 | **Trivy** | Container scan | CVEs en OS + libs de la imagen final | Grype (equivalente, Trivy más maduro) |
| 8 | **Syft** | SBOM | Genera SBOM SPDX de cada imagen | CycloneDX (SPDX más adoptado en gov) |
| 9 | **Cosign (Sigstore)** | Signing | Firma imagen + attest SBOM (keyless OIDC en GH; key propia en Jenkins) | Notary v2 (menos ergonómico) |
| 10 | **GHCR** | Registry | OCI registry gratis integrado a GH | Docker Hub (rate-limit), Harbor (self-host) |
| 11 | **Argo CD** | GitOps CD | Sync declarativo de cluster desde repo GitOps | FluxCD (equivalente, Argo tiene UI mejor) |
| 12 | **kind** | K8s cluster | Cluster K8s local para demo | minikube (más pesado), k3d (equivalente) |
| 13 | **Helm** | K8s templating | Chart parametrizable staging/prod | Kustomize (menos features), raw YAML |
| 14 | **Kyverno** | Policy as Code | Admission controller (verifica firma, limits, non-root) | OPA/Gatekeeper (Rego más difícil de aprender) |
| 15 | **OWASP ZAP** | DAST | Baseline scan contra staging (headers, cookies, XSS pasivo) | Burp (paid), Nuclei (más de infra) |

**Herramientas descartadas explícitamente:**
- ~~GitLab CI~~ — el repo vive en GitHub; sería duplicar GH Actions.
- ~~Ansible/Chef/Puppet~~ — con K8s + Helm no aportan.
- ~~Prometheus/Grafana~~ — fuera del alcance (observabilidad ≠ DevSecOps).

## 4. Cómo correr todo local

### Prerequisitos
- Docker Desktop ≥ 4.30 (Linux/Windows/Mac)
- **[kind](https://kind.sigs.k8s.io/)** ≥ 0.24
- **kubectl** ≥ 1.30
- **helm** ≥ 3.15
- `jq`, `curl`, `bash`

### One-liner
```bash
./scripts/05-bootstrap-all.sh
```

Levanta: cluster kind → ingress-nginx → Argo CD → Kyverno + policies → Jenkins.

### Manual (paso a paso)
```bash
./scripts/00-prerequisitos.sh
./scripts/01-crear-cluster.sh
./scripts/02-install-argocd.sh
./scripts/03-install-kyverno.sh
./scripts/04-deploy-jenkins.sh
```

### Solo docker compose (sin K8s)
```bash
docker compose up -d
# Backend  → http://localhost:8080
# Frontend → http://localhost:5173
# Mailpit  → http://localhost:8025
# Jenkins  → http://localhost:8090
```

### Bajar todo
```bash
./scripts/99-cleanup.sh
```

## 5. Estructura del repo

```
forseti-devsecops/
├── backend/                    ← Spring Boot 3 + Java 21 (código Forseti sanitizado)
├── frontend/                   ← Vue 3 + Vite + PrimeVue
├── .github/workflows/          ← 5 workflows GH Actions (CI, CodeQL, Dep-Check, Gitleaks, Docker+Cosign)
├── jenkins/                    ← Dockerfile + Jenkinsfile + CasC + plugins.txt
├── k8s/
│   └── kind-config.yaml        ← Config del cluster (3 nodos)
├── helm/forseti/               ← Helm chart (backend, frontend, postgres, ingress)
├── argocd/                     ← AppProject + Applications staging/prod
├── kyverno/policies/           ← 5 policies (firma, limits, non-root, no-latest, no-privileged)
├── zap/                        ← Config + script de ZAP baseline
├── gitops-sample/              ← Referencia del repo GitOps separado
├── scripts/                    ← Bootstrap + cleanup
├── docs/                       ← Informe técnico + presentación
├── docker-compose.yml          ← Stack local (backend + frontend + postgres + mailpit + jenkins)
└── README.md                   ← este archivo
```

## 6. Trazabilidad con la consigna académica

| Requisito consigna | Implementación en este repo |
|---|---|
| Flujo CI/CD con build, tests, artefactos, deploy en K8s | `.github/workflows/ci.yml` + `Jenkinsfile` + Argo CD + kind |
| ≥ 2 herramientas de seguridad | 6: CodeQL (SAST), Dep-Check + Trivy (SCA), Gitleaks (secrets), ZAP (DAST), Cosign (signing) |
| Validaciones de políticas | Kyverno (5 policies enforce) |
| Integridad de artefactos | Cosign sign + attest SBOM + verify en pipeline **y** en admission controller |
| Informe técnico ≤ 12 páginas | [`docs/informe-tecnico.md`](docs/informe-tecnico.md) |
| Presentación ≤ 10 láminas | [`docs/presentacion.md`](docs/presentacion.md) |

## 7. Licencia y crédito

Este código deriva del producto **Forseti** de **TARIUS S.A.S.** — se libera como **demo académico** bajo MIT.
La lógica de firma XAdES-BES contra SRI Ecuador es dominio público (Anexo 14 SRI v2.26).

## Estrategia de ramas

Ver [`docs/BRANCHING.md`](docs/BRANCHING.md) para la separación CI vs CD por rama:

- `main` → CD completo (Trivy + Cosign + push GHCR + Jenkins release + prod)
- `dev` → CI + auto-deploy a staging
- `feature/*` → solo CI en el PR
