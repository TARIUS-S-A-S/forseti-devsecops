-- Sprint 4 item 5 — HU-F8 Notas de Crédito.
-- La NC (codDoc=04) modifica un comprobante anterior (típicamente factura). Necesita
-- guardar referencia al doc modificado + motivo para que vaya en el XML SRI.

ALTER TABLE comprobante
    ADD COLUMN doc_modificado_tipo TEXT,          -- codDoc del doc modificado, ej. "01" = factura
    ADD COLUMN doc_modificado_numero TEXT,        -- "001-001-000000001"
    ADD COLUMN doc_modificado_fecha DATE,         -- fecha de emisión del doc original
    ADD COLUMN motivo TEXT;                        -- por qué se emite la NC

COMMENT ON COLUMN comprobante.doc_modificado_tipo IS
    'codDoc SRI del doc modificado (01=factura, 03=liq.compra, 04=nota credito, etc.). '
    'NULL cuando el comprobante NO es nota credito ni nota debito.';
COMMENT ON COLUMN comprobante.motivo IS
    'Motivo de emision del comprobante. Obligatorio en notas de credito/debito.';
