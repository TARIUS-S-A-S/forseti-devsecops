-- Sprint 4 — limpia placeholder hardcoded "5368" en NotaCreditoXmlBuilder.
-- El SRI exige el elemento <contribuyenteEspecial>NNNN</contribuyenteEspecial> solo si
-- la empresa fue designada por el SRI como contribuyente especial (resolucion oficial,
-- raro: <1% de los RUCs). Si no es contribuyente especial, el elemento NO debe ir.
-- Hasta ahora lo metiamos con "5368" hardcoded en TODAS las NC — esto es bug fiscal real.
-- Tras V18, NotaCreditoXmlBuilder lee empresa.codigo_contribuyente_especial; si es NULL
-- o blank, omite el elemento.

ALTER TABLE empresa
    ADD COLUMN codigo_contribuyente_especial VARCHAR(13);  -- nullable; default NULL

COMMENT ON COLUMN empresa.codigo_contribuyente_especial IS
    'Codigo numerico (resolucion SRI) cuando la empresa fue designada contribuyente especial. '
    'NULL en >99% de empresas. Si existe, va al XML como <contribuyenteEspecial>.';
