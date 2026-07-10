#!/usr/bin/env bash
# OWASP ZAP Baseline Scan — Forseti
# ─────────────────────────────────────────────────────────────────────
# Corre un baseline scan (passive + spider) contra la URL de staging.
# NO ejecuta ataques activos (para eso: zap-full-scan.py, más lento).
#
# Uso:
#   ./zap/run-baseline.sh [TARGET_URL]
#
# Ejemplos:
#   ./zap/run-baseline.sh http://localhost:8000
#   ./zap/run-baseline.sh https://staging.forseti.local
# ─────────────────────────────────────────────────────────────────────

set -euo pipefail

TARGET="${1:-http://localhost:8000}"
OUT_DIR="${OUT_DIR:-$(pwd)/zap-reports}"
RULES_FILE="${RULES_FILE:-$(pwd)/zap/rules.tsv}"

mkdir -p "$OUT_DIR"

echo "──────────────────────────────────────────────────────────────"
echo "🕷️  OWASP ZAP Baseline Scan"
echo "  Target:  $TARGET"
echo "  Reports: $OUT_DIR"
echo "  Rules:   $RULES_FILE"
echo "──────────────────────────────────────────────────────────────"

docker run --rm \
  --network host \
  -v "$OUT_DIR":/zap/wrk/:rw \
  -v "$RULES_FILE":/zap/rules.tsv:ro \
  zaproxy/zap-stable:latest \
  zap-baseline.py \
    -t "$TARGET" \
    -c /zap/rules.tsv \
    -r zap-baseline-report.html \
    -x zap-baseline-report.xml \
    -J zap-baseline-report.json \
    -I  # no fallar por WARN (solo FAIL)

echo ""
echo "✅ Reporte HTML:  $OUT_DIR/zap-baseline-report.html"
echo "   Reporte XML :  $OUT_DIR/zap-baseline-report.xml"
echo "   Reporte JSON:  $OUT_DIR/zap-baseline-report.json"
