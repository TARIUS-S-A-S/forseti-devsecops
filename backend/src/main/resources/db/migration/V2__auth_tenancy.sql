-- Forseti — Sprint 1: Auth + Multi-tenancy (schema base)
-- Las tablas tenant-aware tienen empresa_id NOT NULL y RLS (se activa en V4).
-- Tablas sin empresa_id: usuario (cross-tenant, un user puede pertenecer a N empresas),
-- usuario_empresa (la relación), sesion, auditoria (auditoría global).

-- ╔════════════════════════════════════════════════════════╗
-- ║  EMPRESA                                                ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE empresa (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ruc                     VARCHAR(13) NOT NULL UNIQUE
                            CHECK (ruc ~ '^[0-9]{13}$'),
    razon_social            VARCHAR(300) NOT NULL,
    nombre_comercial        VARCHAR(300),
    -- Tipo contribuyente: PN (persona natural), SA, SAS, LTDA, EP, OTRO
    tipo_contribuyente      VARCHAR(20) NOT NULL DEFAULT 'OTRO',
    -- Régimen tributario: RIMPE_NP, RIMPE_EMPRENDEDOR, GENERAL
    regimen_tributario      VARCHAR(30) NOT NULL DEFAULT 'RIMPE_EMPRENDEDOR',
    -- Periodicidad IVA: MENSUAL, SEMESTRAL (varía por régimen)
    periodicidad_iva        VARCHAR(20) NOT NULL DEFAULT 'SEMESTRAL',
    -- Datos de contacto/dirección
    direccion               TEXT,
    ciudad                  VARCHAR(100),
    provincia               VARCHAR(100),
    telefono                VARCHAR(30),
    email                   CITEXT,
    -- Obligado a llevar contabilidad (afecta declaraciones)
    obligado_contabilidad   BOOLEAN NOT NULL DEFAULT false,
    -- Estado del registro en Forseti (no es estado SRI)
    activa                  BOOLEAN NOT NULL DEFAULT true,
    creada_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizada_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_empresa_ruc ON empresa(ruc);

COMMENT ON TABLE empresa IS 'Tenant raíz — toda data tenant-aware referencia empresa.id y usa RLS.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  USUARIO                                                ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE usuario (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   CITEXT NOT NULL UNIQUE,
    nombre                  VARCHAR(200) NOT NULL,
    -- Argon2id hash (formato `$argon2id$v=19$m=...,t=...,p=...$salt$hash`)
    password_hash           TEXT NOT NULL,
    -- Estado de la cuenta
    email_verificado_at     TIMESTAMPTZ,
    activo                  BOOLEAN NOT NULL DEFAULT true,
    -- Tokens transitorios (single-use, expiran)
    verificacion_token      TEXT,
    verificacion_token_expira_at TIMESTAMPTZ,
    recovery_token          TEXT,
    recovery_token_expira_at TIMESTAMPTZ,
    -- 2FA TOTP
    totp_secret             TEXT,           -- base32 secret cifrado en reposo (Sprint 2 con pgcrypto)
    totp_activado_at        TIMESTAMPTZ,
    -- Backup codes (10 códigos hashed con Argon2id; consumidos uno a uno)
    -- Se guardan en tabla aparte: usuario_backup_code
    -- Lockout protection
    intentos_fallidos       INT NOT NULL DEFAULT 0,
    bloqueado_hasta         TIMESTAMPTZ,
    -- Auditoría
    ultimo_login_at         TIMESTAMPTZ,
    ultimo_login_ip         INET,
    creado_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_usuario_email ON usuario(email);
CREATE INDEX idx_usuario_verificacion_token ON usuario(verificacion_token) WHERE verificacion_token IS NOT NULL;
CREATE INDEX idx_usuario_recovery_token ON usuario(recovery_token) WHERE recovery_token IS NOT NULL;

COMMENT ON TABLE usuario IS 'Usuarios cross-tenant. Un usuario puede pertenecer a N empresas con rol distinto.';
COMMENT ON COLUMN usuario.totp_secret IS 'Sprint 2: cifrar en reposo con pgcrypto. Sprint 1: en plain (TODO en application).';

-- Códigos de respaldo 2FA (consumidos one-time)
CREATE TABLE usuario_backup_code (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id              UUID NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    code_hash               TEXT NOT NULL,         -- Argon2id hash del código
    consumido_at            TIMESTAMPTZ,
    creado_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_backup_code_usuario ON usuario_backup_code(usuario_id);

-- ╔════════════════════════════════════════════════════════╗
-- ║  USUARIO_EMPRESA (rol por empresa)                      ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE usuario_empresa (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id              UUID NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    empresa_id              UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    rol                     VARCHAR(20) NOT NULL
                            CHECK (rol IN ('DUENO', 'CONTADORA', 'EMPLEADO', 'ADMIN')),
    invitado_por_usuario_id UUID REFERENCES usuario(id),
    aceptado_at             TIMESTAMPTZ,
    creado_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (usuario_id, empresa_id)
);
CREATE INDEX idx_usuario_empresa_usuario ON usuario_empresa(usuario_id);
CREATE INDEX idx_usuario_empresa_empresa ON usuario_empresa(empresa_id);

-- ╔════════════════════════════════════════════════════════╗
-- ║  SESION                                                 ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE sesion (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id              UUID NOT NULL REFERENCES usuario(id) ON DELETE CASCADE,
    -- Empresa activa para esta sesión (puede cambiarse vía selector empresa)
    empresa_activa_id       UUID REFERENCES empresa(id),
    -- Token opaco: 256-bit aleatorio, base64url. Se rota en cada login.
    token_hash              TEXT NOT NULL UNIQUE,  -- SHA-256 del token real (el real solo viaja en cookie)
    -- Metadata
    ip                      INET,
    user_agent              TEXT,
    creada_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultima_actividad_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_at               TIMESTAMPTZ NOT NULL,
    revocada_at             TIMESTAMPTZ
);
CREATE INDEX idx_sesion_token_hash ON sesion(token_hash);
CREATE INDEX idx_sesion_usuario ON sesion(usuario_id);
CREATE INDEX idx_sesion_activas ON sesion(usuario_id) WHERE revocada_at IS NULL;

COMMENT ON TABLE sesion IS 'Sesiones server-side (no JWT). Token real solo en cookie httpOnly; aquí solo el SHA-256.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  AUDITORIA (cross-tenant)                               ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE auditoria (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id              UUID REFERENCES usuario(id),   -- NULL si es evento del sistema
    empresa_id              UUID REFERENCES empresa(id),   -- NULL si es cross-tenant
    accion                  VARCHAR(100) NOT NULL,
    recurso_tipo            VARCHAR(100),
    recurso_id              UUID,
    -- Metadata estructurada (JSON)
    detalles                JSONB,
    ip                      INET,
    user_agent              TEXT,
    creada_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auditoria_usuario ON auditoria(usuario_id);
CREATE INDEX idx_auditoria_empresa ON auditoria(empresa_id);
CREATE INDEX idx_auditoria_creada ON auditoria(creada_at DESC);
CREATE INDEX idx_auditoria_accion ON auditoria(accion);

COMMENT ON TABLE auditoria IS 'Log de acciones críticas. Retención 7 años (obligación SRI/LOPDP).';

-- ╔════════════════════════════════════════════════════════╗
-- ║  Trigger genérico de updated_at                         ║
-- ╚════════════════════════════════════════════════════════╝
CREATE OR REPLACE FUNCTION set_actualizado_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizado_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_usuario_actualizado_at
    BEFORE UPDATE ON usuario
    FOR EACH ROW EXECUTE FUNCTION set_actualizado_at();

CREATE OR REPLACE FUNCTION set_empresa_actualizada_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizada_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_empresa_actualizada_at
    BEFORE UPDATE ON empresa
    FOR EACH ROW EXECUTE FUNCTION set_empresa_actualizada_at();
