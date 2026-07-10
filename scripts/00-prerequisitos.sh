#!/usr/bin/env bash
# Verifica que todas las herramientas necesarias estén instaladas.
set -euo pipefail

echo "──────────────────────────────────────────────────────────────"
echo "🔍 Forseti DevSecOps — Chequeo de prerequisitos"
echo "──────────────────────────────────────────────────────────────"

FAIL=0
check() {
  local cmd="$1"; local install="$2"
  if command -v "$cmd" >/dev/null 2>&1; then
    echo "  ✅ $cmd"
  else
    echo "  ❌ $cmd  — instalar: $install"
    FAIL=1
  fi
}

check docker "https://docs.docker.com/get-docker/"
check kind   "go install sigs.k8s.io/kind@latest  o  https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
check kubectl "https://kubernetes.io/docs/tasks/tools/"
check helm   "https://helm.sh/docs/intro/install/"
check jq     "sudo apt-get install jq  o  choco install jq"

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "✅ Todo listo. Podés correr:  ./scripts/01-crear-cluster.sh"
else
  echo "❌ Faltan herramientas. Instalá lo pendiente antes de seguir."
  exit 1
fi
