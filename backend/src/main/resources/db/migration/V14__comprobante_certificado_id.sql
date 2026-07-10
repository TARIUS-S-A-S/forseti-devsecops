-- Sprint 3 Fase G (multi-cert): trackear qué certificado firmó cada comprobante.
--
-- Por qué: para soportar UI multi-cert necesitamos saber qué cert firmó qué.
-- Esto permite:
--   - Eliminar definitivo un cert SOLO si nunca firmó nada (no rompe trazabilidad).
--   - Auditar histórico ("¿con qué cert firmamos la factura X?").
--   - Si un cert se compromete, identificar qué comprobantes fueron firmados con él.
--
-- nullable=TRUE para back-compat con comprobantes pre-V14 (que NO tienen tracking).
-- ON DELETE RESTRICT en la FK como defensa en profundidad: aunque CertificadoService
-- valida en código antes de borrar, el constraint a nivel BD garantiza que un cert
-- con comprobantes referenciándolo no pueda borrarse de ninguna forma.

ALTER TABLE comprobante
    ADD COLUMN certificado_id UUID
    REFERENCES certificado_firma(id) ON DELETE RESTRICT;

-- Índice para hacer eficiente el "¿hay comprobantes con este cert?" del eliminar()
-- y la consulta histórica "comprobantes firmados con cert X".
CREATE INDEX idx_comprobante_certificado ON comprobante (certificado_id)
    WHERE certificado_id IS NOT NULL;

COMMENT ON COLUMN comprobante.certificado_id IS
    'Cert que firmo el comprobante. NULL para comprobantes pre-V14 o pendientes de firma. '
    'FK con ON DELETE RESTRICT: no se puede borrar un cert con comprobantes referencicandolo.';
