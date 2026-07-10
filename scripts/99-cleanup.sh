#!/usr/bin/env bash
# Baja TODO — cluster kind + containers docker compose.
set -euo pipefail

echo "🧹 Bajando docker compose..."
docker compose down -v || true

echo "🧹 Borrando cluster kind..."
kind delete cluster --name forseti || true

echo "✅ Cleanup completo."
