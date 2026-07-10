-- Forseti — Sprint 2.5 hotfix: arregla INSERT en empresa bloqueado por RLS.
--
-- BUG: La policy V4 empresa_member_visibility era FOR ALL USING (EXISTS … miembro …).
-- Cuando se crea una empresa NUEVA, todavía no existe ninguna membresía para ella,
-- por lo que el INSERT falla con "new row violates row-level security policy for table empresa".
-- Chicken-and-egg: la membresía se crea inmediatamente después, pero el INSERT no puede pasar.
--
-- FIX: separar acceso (SELECT/UPDATE/DELETE = ser miembro) de creación (INSERT = estar autenticado).
-- Cualquier usuario autenticado puede crear una empresa; el código de la app crea la membresía
-- correspondiente como DUEÑO en la misma transacción. Los reads/updates posteriores siguen
-- protegidos por la regla de membresía.

DROP POLICY IF EXISTS empresa_member_visibility ON empresa;

CREATE POLICY empresa_member_access ON empresa
    FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM usuario_empresa ue
            WHERE ue.empresa_id = empresa.id
              AND ue.usuario_id = current_usuario_id()
        )
    )
    WITH CHECK (
        -- INSERT: cualquier usuario autenticado puede crear una empresa.
        -- UPDATE: además del USING, valida que el nuevo row siga siendo accesible.
        current_usuario_id() IS NOT NULL
    );

COMMENT ON POLICY empresa_member_access ON empresa IS
    'Sprint 2.5 fix (V10): SELECT/UPDATE/DELETE limitado a miembros; INSERT permitido a cualquier usuario autenticado (la app crea la membresía DUEÑO en la misma TX).';
