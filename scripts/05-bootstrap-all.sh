#!/usr/bin/env bash
# Levanta TODO el stack local con un solo comando.
# Ejecutá desde la raíz del repo:  ./scripts/05-bootstrap-all.sh
set -euo pipefail

cd "$(dirname "$0")/.."

echo "══════════════════════════════════════════════════════════════"
echo "  Forseti DevSecOps — Bootstrap completo"
echo "══════════════════════════════════════════════════════════════"

./scripts/00-prerequisitos.sh
./scripts/01-crear-cluster.sh
./scripts/02-install-argocd.sh
./scripts/03-install-kyverno.sh
./scripts/04-deploy-jenkins.sh

echo ""
echo "══════════════════════════════════════════════════════════════"
echo "🎉 Stack local completo levantado."
echo ""
echo "  Jenkins   : http://localhost:8090  (admin / forseti-admin)"
echo "  Argo CD   : kubectl -n argocd port-forward svc/argocd-server 8080:443"
echo "              → https://localhost:8080"
echo "  App       : http://localhost:8000  (via ingress kind)"
echo ""
echo "  Ver policies Kyverno:  kubectl get cpol"
echo "  Ver apps Argo:         kubectl -n argocd get app"
echo "  Correr ZAP:            ./zap/run-baseline.sh http://localhost:8000"
echo "══════════════════════════════════════════════════════════════"
