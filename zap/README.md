# OWASP ZAP — DAST (Dynamic Application Security Testing)

Ejecutamos **ZAP Baseline** contra el ambiente `staging` desplegado por Argo CD.

## Por qué DAST (además de SAST + SCA)

- **SAST** (CodeQL): encuentra bugs en el código sin ejecutarlo.
- **SCA** (Dep-Check + Trivy): encuentra CVEs en dependencias e imágenes.
- **DAST** (ZAP): encuentra vulnerabilidades **en runtime** — headers HTTP faltantes,
  respuestas que filtran info, CSRF/CORS mal, cookies sin flags, etc.

Los tres se complementan. Ninguno solo cubre todo.

## Baseline vs Full Scan

| Modo | Duración | Qué hace |
|---|---|---|
| Baseline (default) | 2-5 min | Passive scan + spider. NO envía payloads maliciosos. |
| Full Scan | 20-60 min | Baseline + Active Scan (SQLi, XSS, etc.) |

Para pipeline: **Baseline** en cada build. **Full** semanal en `main`.

## Ejecutar local

```bash
# 1) Levantar stack (con docker compose o helm en kind)
docker compose up -d

# 2) Correr ZAP
./zap/run-baseline.sh http://localhost:5173

# 3) Ver reporte
xdg-open zap-reports/zap-baseline-report.html   # Linux
start  zap-reports/zap-baseline-report.html     # Windows
```

## En el pipeline

- **GitHub Actions:** disparado en push a `main` (opcional; solo si el CI tiene un runtime)
- **Jenkins:** stage `OWASP ZAP — DAST contra staging` corre después del deploy de Argo CD
- **Bloqueo:** si ZAP reporta FAIL (regla `10202` XSS, `40012` SQLi, etc.), Jenkins marca el build UNSTABLE

## Reglas ignoradas

Ver [`rules.tsv`](./rules.tsv). Todas justificadas — no bajamos umbrales sin razón.
