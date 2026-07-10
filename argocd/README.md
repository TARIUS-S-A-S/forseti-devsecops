# Argo CD — Manifests

Este directorio contiene los manifests que se aplican en el cluster **una sola vez** para conectar Argo CD al repo GitOps.

## Repos que participan del flujo GitOps

```
┌────────────────────────────────┐   ┌──────────────────────────────────────┐
│ forseti-devsecops               │   │ forseti-devsecops-gitops              │
│  ─ código fuente                │   │  ─ Helm values de staging + prod      │
│  ─ Dockerfiles                  │   │  ─ tags de imagen (bump por Jenkins)  │
│  ─ Helm chart (helm/forseti)    │   │  ─ SOURCE OF TRUTH del cluster        │
│  ─ Jenkinsfile                  │   │                                       │
│  ─ workflows GH Actions         │   │  ← lo watchea Argo CD                 │
└────────────────────────────────┘   └──────────────────────────────────────┘
```

## Aplicar

```bash
# 1) Instalar Argo CD (upstream)
kubectl create namespace argocd
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 2) AppProject + Applications
kubectl apply -f argocd/appproject.yaml
kubectl apply -f argocd/application-staging.yaml
kubectl apply -f argocd/application-prod.yaml

# 3) Password inicial
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d
echo

# 4) UI
kubectl -n argocd port-forward svc/argocd-server 8080:443
# → https://localhost:8080  (admin / <password del paso 3>)
```

## Estructura esperada en el repo GitOps

```
forseti-devsecops-gitops/
├── environments/
│   ├── staging/
│   │   └── values.yaml       # backendTag: "sha-abc1234"
│   └── prod/
│       └── values.yaml       # backendTag: "v1.2.3"
└── README.md
```

Jenkins hace `sed` sobre `environments/staging/values.yaml` para bumpear los tags
tras firmar las imágenes con Cosign. Argo CD detecta el commit y sync-ea.
