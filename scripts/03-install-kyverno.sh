#!/usr/bin/env bash
# Instala Kyverno + aplica las policies.
set -euo pipefail

KYVERNO_VERSION="v1.12.5"

echo "──────────────────────────────────────────────────────────────"
echo "🛡️  Instalando Kyverno ${KYVERNO_VERSION}"
echo "──────────────────────────────────────────────────────────────"

kubectl create -f "https://github.com/kyverno/kyverno/releases/download/${KYVERNO_VERSION}/install.yaml" \
  --save-config --dry-run=client -o yaml | kubectl apply -f -

echo ""
echo "⏳ Esperando pods de Kyverno (hasta 3 min)..."
kubectl -n kyverno wait --for=condition=available --timeout=180s deploy --all

echo ""
echo "──────────────────────────────────────────────────────────────"
echo "📥 Aplicando policies"
echo "──────────────────────────────────────────────────────────────"
kubectl apply -f kyverno/policies/

echo ""
echo "✅ Policies activas:"
kubectl get cpol
