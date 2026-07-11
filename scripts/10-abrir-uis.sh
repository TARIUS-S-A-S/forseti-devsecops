#!/usr/bin/env bash
# Abre todos los port-forwards para acceder a las UIs desde localhost.
# Corre esto ANTES de la expo (o cuando quieras acceder a las UIs).
set -euo pipefail

echo "──────────────────────────────────────────────────────────────"
echo "🌐 Abriendo port-forwards para las UIs del stack Forseti"
echo "──────────────────────────────────────────────────────────────"

# Matar port-forwards previos (si los hay)
pkill -f "kubectl.*port-forward" 2>/dev/null || true
sleep 1

# Argo CD (HTTPS 8080)
kubectl -n argocd port-forward svc/argocd-server 8080:443 \
  > /tmp/pf-argocd.log 2>&1 &
echo "  ✅ Argo CD           → https://localhost:8080"

# Forseti frontend (HTTP 5173)
kubectl -n forseti-staging port-forward svc/forseti-frontend 5173:80 \
  > /tmp/pf-frontend.log 2>&1 &
echo "  ✅ Forseti UI        → http://localhost:5173"

# Forseti backend (HTTP 8081)
kubectl -n forseti-staging port-forward svc/forseti-backend 8081:8080 \
  > /tmp/pf-backend.log 2>&1 &
echo "  ✅ Backend API       → http://localhost:8081/actuator/health/readiness"

# Postgres (5432)
kubectl -n forseti-staging port-forward svc/forseti-postgres 5432:5432 \
  > /tmp/pf-postgres.log 2>&1 &
echo "  ✅ Postgres          → localhost:5432 (forseti / forseti_demo_change_me)"

sleep 3

echo ""
echo "──────────────────────────────────────────────────────────────"
echo "🔑 Credenciales"
echo "──────────────────────────────────────────────────────────────"
echo "  Jenkins  → http://localhost:8090"
echo "    admin / forseti-admin"
echo ""
echo "  Argo CD  → https://localhost:8080"
echo -n "    admin / "
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' 2>/dev/null | base64 -d
echo ""
echo ""
echo "──────────────────────────────────────────────────────────────"
echo "💡 Si un port-forward muere, correr este script de nuevo."
echo "──────────────────────────────────────────────────────────────"
