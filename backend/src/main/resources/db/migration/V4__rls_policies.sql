-- Forseti — Sprint 1: Row Level Security multi-tenant
-- Patrón: cada request setea SET LOCAL app.empresa_id = '<uuid>' al inicio de la TX.
-- Las policies de RLS filtran toda fila que no pertenezca a esa empresa.
--
-- IMPORTANTE: forseti_app NO tiene BYPASSRLS. forseti (owner Flyway) sí lo tiene por default.
-- Para que el owner también obedezca RLS en tests:
-- - Las tablas usan FORCE ROW LEVEL SECURITY (afecta también al owner).

-- ╔════════════════════════════════════════════════════════╗
-- ║  Helper: leer empresa actual de la sesión               ║
-- ╚════════════════════════════════════════════════════════╝
CREATE OR REPLACE FUNCTION current_empresa_id() RETURNS UUID AS $$
BEGIN
    -- Lee SET LOCAL app.empresa_id; si no está, retorna NULL → RLS bloquea todo.
    RETURN NULLIF(current_setting('app.empresa_id', true), '')::UUID;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION current_empresa_id() IS 'Lee el empresa_id de la sesión actual. NULL si no está seteado → RLS bloquea.';

CREATE OR REPLACE FUNCTION current_usuario_id() RETURNS UUID AS $$
BEGIN
    RETURN NULLIF(current_setting('app.usuario_id', true), '')::UUID;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- ╔════════════════════════════════════════════════════════╗
-- ║  Activar RLS en tablas tenant-aware (solo empresa)      ║
-- ╚════════════════════════════════════════════════════════╝
-- Nota: en V2 solo empresa es directamente tenant-aware.
-- Las tablas Sprint 2+ (factura, compra, etc.) van a aplicar el mismo patrón.

-- usuario_empresa: necesita RLS también — un usuario solo ve sus propias relaciones
ALTER TABLE usuario_empresa ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario_empresa FORCE ROW LEVEL SECURITY;

-- Política: ver solo relaciones donde el usuario actual está involucrado
-- (un usuario puede ver TODAS sus empresas, pero no las relaciones de otros usuarios)
CREATE POLICY usuario_empresa_self_or_admin ON usuario_empresa
    FOR ALL
    USING (
        usuario_id = current_usuario_id()
        OR EXISTS (
            SELECT 1 FROM usuario_empresa ue
            WHERE ue.usuario_id = current_usuario_id()
              AND ue.empresa_id = usuario_empresa.empresa_id
              AND ue.rol IN ('DUENO', 'ADMIN')
        )
    );

-- Auditoría: ver solo eventos del empresa actual o cross-tenant del propio user
ALTER TABLE auditoria ENABLE ROW LEVEL SECURITY;
ALTER TABLE auditoria FORCE ROW LEVEL SECURITY;

CREATE POLICY auditoria_tenant_visibility ON auditoria
    FOR SELECT
    USING (
        empresa_id = current_empresa_id()
        OR (empresa_id IS NULL AND usuario_id = current_usuario_id())
    );

-- INSERT en auditoria siempre permitido (el código de la app es el responsable)
CREATE POLICY auditoria_insert_open ON auditoria
    FOR INSERT
    WITH CHECK (true);

-- Empresa: un usuario solo puede ver/editar las empresas a las que pertenece
ALTER TABLE empresa ENABLE ROW LEVEL SECURITY;
ALTER TABLE empresa FORCE ROW LEVEL SECURITY;

CREATE POLICY empresa_member_visibility ON empresa
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM usuario_empresa ue
            WHERE ue.empresa_id = empresa.id
              AND ue.usuario_id = current_usuario_id()
        )
    );

-- Sprint 2+ las migraciones agregarán políticas tipo:
-- ALTER TABLE factura ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE factura FORCE ROW LEVEL SECURITY;
-- CREATE POLICY factura_tenant ON factura FOR ALL USING (empresa_id = current_empresa_id());

-- ╔════════════════════════════════════════════════════════╗
-- ║  Tablas SIN RLS (acceso global, controlado por app)     ║
-- ╚════════════════════════════════════════════════════════╝
-- usuario: el AuthService maneja control de acceso (no se filtra por tenant)
-- sesion: idem
-- usuario_backup_code: la app filtra por usuario_id

-- Permisos finales: explicitar que forseti_app puede usar las funciones
GRANT EXECUTE ON FUNCTION current_empresa_id() TO forseti_app;
GRANT EXECUTE ON FUNCTION current_usuario_id() TO forseti_app;
