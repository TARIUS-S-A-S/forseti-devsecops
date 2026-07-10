#!/usr/bin/env bash
# Levanta Jenkins vía docker compose (más simple que meterlo al cluster).
set -euo pipefail

echo "──────────────────────────────────────────────────────────────"
echo "🏗️  Building imagen Jenkins con Trivy + Syft + Cosign + Helm + kubectl"
echo "──────────────────────────────────────────────────────────────"

docker compose build jenkins

echo ""
echo "──────────────────────────────────────────────────────────────"
echo "🚀 Levantando Jenkins"
echo "──────────────────────────────────────────────────────────────"

docker compose up -d jenkins

echo ""
echo "⏳ Esperando Jenkins (hasta 3 min)..."
for i in $(seq 1 36); do
  if curl -fsS http://localhost:8090/login >/dev/null 2>&1; then
    echo ""
    echo "✅ Jenkins arriba en http://localhost:8090"
    echo "   Login: admin / forseti-admin  (definido en jenkins/casc.yaml)"
    exit 0
  fi
  echo -n "."
  sleep 5
done

echo ""
echo "❌ Jenkins no respondió en 3 min. Revisá: docker compose logs jenkins"
exit 1
