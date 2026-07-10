-- Forseti — Baseline schema (Sprint 0)
-- Tablas de usuarios y empresas vacías por ahora; Sprint 1 las llena completas con RLS

-- Extensiones útiles
CREATE EXTENSION IF NOT EXISTS "pgcrypto";       -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";         -- emails case-insensitive

-- Tabla de healthcheck/info (placeholder para Sprint 0)
CREATE TABLE IF NOT EXISTS forseti_info (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO forseti_info (key, value) VALUES
    ('version', '0.1.0-SNAPSHOT'),
    ('product', 'Forseti'),
    ('company', 'TARIUS S.A.S')
ON CONFLICT (key) DO NOTHING;

-- Sprint 1 (próxima migración): usuario, empresa, usuario_empresa, sesion, auditoria
-- Sprint 2: perfil_tributario, obligacion_catalogo, obligacion_empresa, p12_cifrado
-- Sprint 3: factura, factura_detalle, secuencial, sri_envio, sri_autorizacion
