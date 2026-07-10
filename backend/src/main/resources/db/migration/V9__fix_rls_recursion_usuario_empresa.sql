-- Forseti — Sprint 2.5 hotfix: arregla recursión infinita en la policy de usuario_empresa.
--
-- BUG: La policy V4 usuario_empresa_self_or_admin tenía:
--   USING (usuario_id = current_usuario_id()
--          OR EXISTS (SELECT 1 FROM usuario_empresa ue WHERE ...))
-- El subquery interno dispara la misma policy → recursión infinita.
-- Postgres detecta esto y rechaza con "infinite recursion detected in policy".
--
-- FIX: Reemplazar el EXISTS recursivo por un "bit de autoridad" externo:
-- una GUC variable que la app setea TRAS validar (en código) que el actor es DUEÑO/ADMIN.
-- La policy lee la variable; el rol DUEÑO/ADMIN no se consulta dentro de la policy.

DROP POLICY IF EXISTS usuario_empresa_self_or_admin ON usuario_empresa;

CREATE POLICY usuario_empresa_visibility ON usuario_empresa
    FOR ALL
    USING (
        -- Caso 1: ver mis propias membresías
        usuario_id = current_usuario_id()
        -- Caso 2: ver membresías de una empresa donde la app me validó como gestor
        --         (la app setea SET LOCAL app.gestor_de_empresa = '<empresa_uuid>' antes de leer)
        OR NULLIF(current_setting('app.gestor_de_empresa', true), '')::uuid = empresa_id
    );

COMMENT ON POLICY usuario_empresa_visibility ON usuario_empresa IS
    'Sprint 2.5 fix (V9): ver mis membresías, o las de una empresa donde la app marcó que soy gestor mediante SET LOCAL app.gestor_de_empresa.';
