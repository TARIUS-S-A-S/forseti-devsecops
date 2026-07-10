# Forseti Backend

> SaaS de facturación electrónica SRI Ecuador + cumplimiento tributario/societario + gestión.
> Producto de **TARIUS S.A.S** — primer cliente: TARIUS (dogfooding).

[![CI](https://github.com/TARIUS-S-A-S/forseti-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/TARIUS-S-A-S/forseti-backend/actions)

## Stack

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 21 LTS |
| Framework | Spring Boot 3.4 |
| Build | Maven |
| Base de datos | PostgreSQL 17 + Flyway (migraciones en `src/main/resources/db/migration/`) |
| Auth | Spring Security + sesión cookie httpOnly + Argon2id + 2FA TOTP |
| Multi-tenancy | RLS (Row Level Security) + `SET LOCAL app.empresa_id` |
| Jobs async | JobRunr (persistencia en Postgres, sin Redis/RabbitMQ) |
| Firma SRI | xades4j (XAdES-BES) + BouncyCastle (PKCS#12 legacy) |
| SOAP SRI | Apache HttpClient5 |
| PDFs | openhtmltopdf (RIDE + documentos) |
| Correo | Spring Mail + Brevo (300/día free) |
| Tests | JUnit 5 + AssertJ + Testcontainers (Postgres real) + ArchUnit + WireMock |

## Quickstart local

```bash
# 1. Levantar Postgres + Mailpit + WireMock
cd infra && docker compose -f compose.local.yml up -d

# 2. Correr backend
mvn spring-boot:run

# 3. Verificar
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/public/info
```

## Estructura

```
src/main/java/ec/tarius/forseti/
├── ForsetiApplication.java
├── config/         — seguridad, JobRunr, cache, OpenAPI
├── auth/           — login, sesiones, 2FA TOTP (Sprint 1)
├── usuario/        — usuarios y roles por empresa (Sprint 1)
├── empresa/        — tenant, perfil tributario, .p12 (Sprint 2)
├── factura/        — emisión, NC, RIDE (Sprint 3-4)
├── sri/            — XML, firma, SOAP, autorización (Sprint 3)
├── compras/        — XML compras + flujo caja (Sprint 5)
├── contabilidad/   — doble partida + plan cuentas (Sprint 6)
├── declaracion/    — F104, renta, ATS, APS (Sprint 7-8)
├── reporte/        — estados, calendario (Sprint 9)
└── shared/         — utilidades, controllers públicos, errors
```

## Migraciones (Flyway)

```bash
mvn flyway:info       # ver estado
mvn flyway:migrate    # aplicar pendientes
```

Las migraciones viven en `src/main/resources/db/migration/V<n>__<descripcion>.sql` y se aplican automáticamente al arrancar.

## Tests

```bash
mvn test                          # todos
mvn test -Dtest=ArchitectureTest  # solo ArchUnit
mvn verify                        # incluye integración con Testcontainers
```

## Deploy

- **Staging:** push a `dev` → CI deploy a `staging.forseti.tarius.ec`
- **Producción:** PR de `dev` a `main` → CI deploy a `forseti.tarius.ec`

Infra: ver `infra/` (Caddyfile, docker-compose, scripts backup).

## Documentación

- Plan completo: [`productos/forseti/documento-maestro.md`](../forseti/documento-maestro.md)
- Roadmap: [`productos/forseti/roadmap.md`](../forseti/roadmap.md)
- Identidad de marca: [`07-marca/forseti/`](../../_marca-tokens/forseti.tokens.json) (v3.0)

## Convenciones

- Commits: conventional commits en español (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`, `test:`).
- Sprints: rama `dev` (integración) → `main` (producción). Cada sprint cierra con tag `v<x.y.z>`.

---

**© TARIUS S.A.S — Quito, Ecuador. Producto comercial — IP reservada.**
