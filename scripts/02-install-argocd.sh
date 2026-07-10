#!/usr/bin/env bash
# Instala Argo CD y aplica los AppProject + Applications.
set -euo pipefail

echo "──────────────────────────────────────────────────────────────"
echo "🐙 Instalando Argo CD"
echo "──────────────────────────────────────────────────────────────"

kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

echo ""
echo "⏳ Esperando pods de Argo CD (hasta 3 min)..."
kubectl -n argocd wait --for=condition=available --timeout=180s deploy --all

echo ""
echo "──────────────────────────────────────────────────────────────"
echo "📥 Aplicando AppProject + Applications"
echo "──────────────────────────────────────────────────────────────"
kubectl apply -f argocd/appproject.yaml
kubectl apply -f argocd/application-staging.yaml
# Prod NO se aplica auto en demo — descomentar cuando se apunte a repo GitOps real
# kubectl apply -f argocd/application-prod.yaml

echo ""
echo "──────────────────────────────────────────────────────────────"
echo "🔑 Password inicial de admin:"
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d
echo ""
echo ""
echo "🌐 Acceder a la UI (en otra terminal):"
echo "   kubectl -n argocd port-forward svc/argocd-server 8080:443"
echo "   → https://localhost:8080  (admin / <password de arriba>)"
