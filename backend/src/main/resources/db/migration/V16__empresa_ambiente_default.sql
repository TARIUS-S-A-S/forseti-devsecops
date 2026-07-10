-- Sprint 4 item 4 — Ambiente default por empresa.
-- Hoy el ambiente (PRUEBAS/PRODUCCION) se pasa como query param en cada emisión.
-- A partir de Sprint 4 es configuración persistente por empresa: el front lo lee
-- y lo manda explícitamente. Cambiarlo a PRODUCCION requiere:
--   - cert activo,
--   - perfil tributario vigente,
--   - al menos 1 establecimiento con secuencial PRODUCCION configurado.
-- (Las validaciones viven en EmpresaService.cambiarAmbiente()).

ALTER TABLE empresa
    ADD COLUMN ambiente_default TEXT NOT NULL DEFAULT 'PRUEBAS'
    CHECK (ambiente_default IN ('PRUEBAS','PRODUCCION'));

COMMENT ON COLUMN empresa.ambiente_default IS
    'Ambiente SRI por defecto al emitir comprobantes desde esta empresa. '
    'PRUEBAS = sandbox SRI (facturas no fiscales). PRODUCCION = facturas reales con '
    'efectos legales y tributarios. Cambiar a PRODUCCION requiere validaciones.';
