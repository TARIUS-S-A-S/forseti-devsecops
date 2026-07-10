## ¿Qué hace este PR?

<!-- Descripción concisa del cambio: feat/fix/refactor/docs/chore -->

## Sprint / HU relacionada

<!-- Ej: Sprint 1, HU-A2 -->

## Checklist DoD

- [ ] Lint en verde local (`mvn -B compile`)
- [ ] Tests en verde local (`mvn -B test`)
- [ ] ArchUnit pasa (no se rompieron reglas de arquitectura)
- [ ] Sin secretos (.env, .p12, keys) commiteados — verificado por gitleaks en CI
- [ ] Si tocó schema: migración Flyway con número siguiente y descripción clara
- [ ] Si tocó endpoint público: documentado en OpenAPI / Javadoc
- [ ] Si tocó perfil-tributario o SRI: tests cubren al menos 1 caso golden + 1 edge

## Notas para el reviewer

<!-- Cosas raras, decisiones discutibles, áreas a mirar con más detalle -->
