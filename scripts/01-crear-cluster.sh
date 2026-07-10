#!/usr/bin/env bash
# Crea el cluster kind + instala Ingress-nginx.
set -euo pipefail

CLUSTER_NAME="forseti"

echo "──────────────────────────────────────────────────────────────"
echo "🚀 Creando cluster kind: $CLUSTER_NAME"
echo "──────────────────────────────────────────────────────────────"

if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
  echo "  ⚠️  Cluster '$CLUSTER_NAME' ya existe. Skip."
else
  kind create cluster --config k8s/kind-config.yaml --name "$CLUSTER_NAME"
fi

echo ""
echo "──────────────────────────────────────────────────────────────"
echo "📦 Instalando ingress-nginx"
echo "──────────────────────────────────────────────────────────────"
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

echo ""
echo "⏳ Esperando ingress-nginx-controller (hasta 3 min)..."
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s

echo ""
echo "✅ Cluster listo. Nodos:"
kubectl get nodes -o wide
