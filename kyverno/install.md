# Kyverno — Instalación en kind

```bash
# 1) Instalar Kyverno via manifest oficial
kubectl create -f https://github.com/kyverno/kyverno/releases/download/v1.12.5/install.yaml

# 2) Esperar a que esté listo
kubectl -n kyverno wait --for=condition=available --timeout=120s deploy --all

# 3) Aplicar las policies del repo
kubectl apply -f kyverno/policies/
```

Las policies se ejecutan en `enforce` mode (bloquean deploys que violan).
Para probar sin bloquear, cambiar `validationFailureAction: Enforce` → `Audit`.
