# Documentos

- **`informe-tecnico.md`** — informe técnico completo (12 páginas). Convertir a PDF con:
  ```bash
  pandoc docs/informe-tecnico.md -o docs/informe-tecnico.pdf \
    --pdf-engine=xelatex \
    --toc \
    --number-sections \
    -V mainfont="Georgia" -V sansfont="Arial" -V monofont="Consolas"
  ```

- **`presentacion.md`** — 10 láminas (formato Marp). Convertir a PDF/PPTX con:
  ```bash
  npx @marp-team/marp-cli docs/presentacion.md -o docs/presentacion.pdf
  npx @marp-team/marp-cli docs/presentacion.md -o docs/presentacion.pptx
  ```

  O usar la [extensión Marp para VSCode](https://marketplace.visualstudio.com/items?itemName=marp-team.marp-vscode) para preview + export.

## Capturas para el informe

Guardar en `docs/capturas/`:

1. `01-github-actions-status.png` — matrix de workflows verdes
2. `02-codeql-security-tab.png` — dashboard CodeQL sin alerts open
3. `03-dep-check-report.png` — reporte HTML OWASP Dep-Check
4. `04-jenkins-pipeline-graph.png` — Blue Ocean con todos los stages verdes
5. `05-trivy-scan-result.png` — output de `trivy image` con severity table
6. `06-cosign-sign-verify.png` — terminal con `cosign sign` + `cosign verify`
7. `07-argocd-ui.png` — Argo CD UI con Application `forseti-staging` Synced/Healthy
8. `08-kyverno-block-latest.png` — kubectl rechazado por Kyverno
9. `09-zap-report.png` — reporte HTML de ZAP baseline
10. `10-ghcr-signed-image.png` — GHCR con tag `sig` visible al lado de la imagen
