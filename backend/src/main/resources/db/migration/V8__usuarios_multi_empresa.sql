-- Forseti — Sprint 2.5: usuarios multi-empresa con username + invitaciones
--
-- Cambios al modelo de usuario:
--   - email pasa a ser opcional (algunos empleados no tienen email corporativo)
--   - se agrega username opcional (alternativa al email para login)
--   - al menos uno de los dos debe existir (constraint CHECK)
--   - flag debe_cambiar_password para usuarios creados por el dueño con password temporal
--
-- Invitaciones:
--   - Tabla invitacion_empresa: el dueño invita por email; token único 256-bit
--   - SIN RLS: el token es secret. El control de acceso lo hace la app
--     (endpoints autenticados verifican rol; endpoint público requiere token).

-- ╔════════════════════════════════════════════════════════╗
-- ║  ALTER usuario — email opcional + username + flag       ║
-- ╚════════════════════════════════════════════════════════╝

-- Drop el UNIQUE constraint anterior (lo recreamos como partial unique index)
ALTER TABLE usuario DROP CONSTRAINT usuario_email_key;

ALTER TABLE usuario ALTER COLUMN email DROP NOT NULL;

ALTER TABLE usuario ADD COLUMN username CITEXT;

ALTER TABLE usuario ADD COLUMN debe_cambiar_password BOOLEAN NOT NULL DEFAULT false;

-- Al menos uno de email/username
ALTER TABLE usuario ADD CONSTRAINT usuario_identificador_chk
    CHECK (email IS NOT NULL OR username IS NOT NULL);

-- Unicidad parcial — solo aplica cuando no es NULL
CREATE UNIQUE INDEX usuario_email_unique ON usuario(email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX usuario_username_unique ON usuario(username) WHERE username IS NOT NULL;

-- El índice anterior idx_usuario_email queda como search index (no único)
DROP INDEX IF EXISTS idx_usuario_email;
CREATE INDEX idx_usuario_email ON usuario(email) WHERE email IS NOT NULL;
CREATE INDEX idx_usuario_username ON usuario(username) WHERE username IS NOT NULL;

COMMENT ON COLUMN usuario.username IS 'Sprint 2.5: alternativa a email para login. Único cuando no es NULL.';
COMMENT ON COLUMN usuario.debe_cambiar_password IS 'Sprint 2.5: true para usuarios creados por el dueño con password temporal. Forzar cambio al primer login.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  INVITACION_EMPRESA — invitar por email                 ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE invitacion_empresa (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id               UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    -- Snapshot del nombre de la empresa al momento de invitar.
    -- Permite que el endpoint público /invitaciones/{token} muestre "Te invitaron a X"
    -- sin necesitar bypassear RLS de la tabla empresa.
    empresa_razon_social     VARCHAR(300) NOT NULL,
    email                    CITEXT NOT NULL,
    nombre_invitado          VARCHAR(200),
    rol                      VARCHAR(20) NOT NULL
                             CHECK (rol IN ('DUENO', 'CONTADORA', 'EMPLEADO', 'ADMIN')),
    -- Token opaco 256-bit (URL-safe base64). El secret real solo está acá; viaja por correo.
    token                    TEXT NOT NULL UNIQUE,
    invitada_por_usuario_id  UUID REFERENCES usuario(id),
    creada_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    expira_at                TIMESTAMPTZ NOT NULL,
    aceptada_at              TIMESTAMPTZ,
    aceptada_por_usuario_id  UUID REFERENCES usuario(id),
    cancelada_at             TIMESTAMPTZ,
    cancelada_por_usuario_id UUID REFERENCES usuario(id)
);
CREATE INDEX idx_invitacion_empresa ON invitacion_empresa(empresa_id);
CREATE INDEX idx_invitacion_token ON invitacion_empresa(token);
CREATE INDEX idx_invitacion_pendientes ON invitacion_empresa(empresa_id, email)
    WHERE aceptada_at IS NULL AND cancelada_at IS NULL;

COMMENT ON TABLE invitacion_empresa IS 'Sprint 2.5: invitaciones por email a una empresa. Token único; expira en 7 días por default. SIN RLS: el token es el secret; endpoints públicos lo validan, endpoints autenticados verifican rol.';

-- NO se aplica RLS a invitacion_empresa: el control de acceso vive en la app.
-- Razón: el flow "aceptar invitación" no tiene contexto de tenant (el usuario aún no es miembro).
-- La policy podría hacerse con bypass por bandera, pero es más simple y auditable hacerlo en código.

-- ╔════════════════════════════════════════════════════════╗
-- ║  Solo un DUEÑO mínimo por empresa                       ║
-- ╚════════════════════════════════════════════════════════╝
-- Trigger que impide eliminar la última membresía DUEÑO de una empresa.
-- Sprint 2.5: protege contra "quedarse sin dueño" al expulsar miembros.
CREATE OR REPLACE FUNCTION usuario_empresa_minimo_un_dueno()
RETURNS TRIGGER AS $$
DECLARE
    duenos_restantes INT;
    empresa_referenciada UUID;
BEGIN
    -- En DELETE o UPDATE de rol, contar dueños restantes para la empresa afectada
    IF (TG_OP = 'DELETE') THEN
        empresa_referenciada := OLD.empresa_id;
        IF OLD.rol = 'DUENO' THEN
            SELECT COUNT(*) INTO duenos_restantes
            FROM usuario_empresa
            WHERE empresa_id = empresa_referenciada AND rol = 'DUENO' AND id <> OLD.id;
            IF duenos_restantes = 0 THEN
                RAISE EXCEPTION 'No se puede quitar al último DUEÑO de la empresa';
            END IF;
        END IF;
        RETURN OLD;
    ELSIF (TG_OP = 'UPDATE') THEN
        empresa_referenciada := NEW.empresa_id;
        IF OLD.rol = 'DUENO' AND NEW.rol <> 'DUENO' THEN
            SELECT COUNT(*) INTO duenos_restantes
            FROM usuario_empresa
            WHERE empresa_id = empresa_referenciada AND rol = 'DUENO' AND id <> NEW.id;
            IF duenos_restantes = 0 THEN
                RAISE EXCEPTION 'No se puede degradar al último DUEÑO de la empresa';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_usuario_empresa_minimo_dueno
    BEFORE UPDATE OR DELETE ON usuario_empresa
    FOR EACH ROW EXECUTE FUNCTION usuario_empresa_minimo_un_dueno();
