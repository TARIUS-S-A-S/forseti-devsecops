-- Forseti — Sprint 3: emisión de comprobantes electrónicos al SRI
--
-- Esquema mínimo para HU-F9 (motor) y HU-F7 parte 1 (factura).
--   - comprobante: cabecera + estado + clave de acceso + XML firmado/autorizado
--   - comprobante_detalle: ítems de la factura (cantidad × precio + impuesto)
--   - comprobante_evento: bitácora de transiciones de estado (audit fino)
--
-- Reglas:
--   - Toda tabla tenant-aware lleva empresa_id NOT NULL + RLS + FORCE RLS (RNF-7).
--   - clave_acceso es UNIQUE global: dos empresas no pueden colisionar (los 49 dígitos
--     incluyen el RUC del emisor y el secuencial; el aleatorio extra evita choques).
--   - numero_comprobante es UNIQUE por (empresa, tipo, ambiente): el SRI rechaza duplicados.
--   - xml_firmado y xml_autorizado son BYTEA — pueden ser grandes (>50KB con respuesta SRI),
--     no Strings, para no inflar memoria al listar.

-- ╔════════════════════════════════════════════════════════╗
-- ║  COMPROBANTE — cabecera + estado                        ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE comprobante (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id                  UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    establecimiento_id          UUID NOT NULL REFERENCES establecimiento(id),
    punto_emision_id            UUID NOT NULL REFERENCES punto_emision(id),
    secuencial_id               UUID NOT NULL REFERENCES secuencial(id),

    tipo_comprobante            VARCHAR(30) NOT NULL
                                CHECK (tipo_comprobante IN ('FACTURA','NOTA_CREDITO','NOTA_DEBITO',
                                                            'RETENCION','GUIA_REMISION','LIQUIDACION_COMPRA')),
    ambiente                    VARCHAR(15) NOT NULL
                                CHECK (ambiente IN ('PRUEBAS','PRODUCCION')),
    tipo_emision                VARCHAR(10) NOT NULL DEFAULT 'NORMAL'
                                CHECK (tipo_emision IN ('NORMAL')),
    -- Número que sale del secuencial (1..999999999)
    secuencial_numero           BIGINT NOT NULL CHECK (secuencial_numero > 0),
    -- Formato SRI legible "001-001-000000001"
    numero_comprobante          VARCHAR(17) NOT NULL,
    -- 49 dígitos (ddMMyyyy+codDoc+ruc+amb+serie+sec+aleatorio+tipoEmision+dv) — UNIQUE global
    clave_acceso                VARCHAR(49) NOT NULL UNIQUE,
    fecha_emision               DATE NOT NULL,
    estado                      VARCHAR(20) NOT NULL DEFAULT 'BORRADOR'
                                CHECK (estado IN ('BORRADOR','FIRMADA','ENVIADA','EN_PROCESO',
                                                  'AUTORIZADA','DEVUELTA','NO_AUTORIZADA','ABANDONADA')),

    -- Receptor (cliente para factura)
    -- Tipos SRI: 04=RUC, 05=cédula, 06=pasaporte, 07=consumidor final, 08=ID exterior
    receptor_tipo_id            VARCHAR(2) NOT NULL
                                CHECK (receptor_tipo_id IN ('04','05','06','07','08')),
    receptor_identificacion     VARCHAR(20) NOT NULL,
    receptor_razon_social       VARCHAR(300) NOT NULL,
    receptor_direccion          TEXT,
    receptor_email              TEXT,
    receptor_telefono           VARCHAR(30),

    -- Totales (denormalizados desde detalles para listados rápidos)
    subtotal_sin_impuestos      NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_descuento             NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_iva                   NUMERIC(14,2) NOT NULL DEFAULT 0,
    -- subtotal por tarifa (0%, 15%, exento, no objeto) — para infoFactura totalConImpuestos
    importe_total               NUMERIC(14,2) NOT NULL DEFAULT 0,
    moneda                      VARCHAR(10) NOT NULL DEFAULT 'DOLAR',

    -- Forma de pago (1..N en SRI; al inicio guardamos una en columna; histórico en JSONB)
    -- Códigos SRI: 01=efectivo, 15=compensación, 16=tarjeta débito, 17=dinero electrónico, 18=tarjeta prepago,
    -- 19=tarjeta crédito, 20=otros con utilización del sistema financiero, 21=endoso títulos
    forma_pago                  VARCHAR(2) NOT NULL DEFAULT '01',
    plazo_dias                  INT NOT NULL DEFAULT 0 CHECK (plazo_dias >= 0),

    -- XML firmado (lo que se envía al SRI) y XML autorizado (respuesta con autorización)
    xml_firmado                 BYTEA,
    xml_autorizado              BYTEA,
    -- En el esquema offline post-2024 el SRI usa la clave_acceso como número de autorización
    numero_autorizacion         VARCHAR(49),
    fecha_autorizacion          TIMESTAMPTZ,

    -- Respuesta SRI (último estado conocido)
    mensaje_sri                 TEXT,
    codigo_error_sri            VARCHAR(20),

    -- Job runner / reintentos
    intentos_envio              INT NOT NULL DEFAULT 0,
    ultimo_intento_at           TIMESTAMPTZ,
    siguiente_intento_at        TIMESTAMPTZ,

    -- Auditoría
    creado_por_usuario_id       UUID REFERENCES usuario(id),
    creado_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Una empresa no puede tener 2 comprobantes con el mismo número en el mismo ambiente
    UNIQUE (empresa_id, tipo_comprobante, ambiente, numero_comprobante)
);
CREATE INDEX idx_comprobante_empresa ON comprobante(empresa_id);
CREATE INDEX idx_comprobante_estado ON comprobante(empresa_id, estado);
CREATE INDEX idx_comprobante_fecha ON comprobante(empresa_id, fecha_emision DESC);
-- Worker JobRunr: ¿qué comprobantes hay que (re)procesar?
CREATE INDEX idx_comprobante_pendientes ON comprobante(siguiente_intento_at)
    WHERE estado IN ('FIRMADA','ENVIADA','EN_PROCESO') AND siguiente_intento_at IS NOT NULL;

COMMENT ON TABLE comprobante IS 'Cabecera de comprobantes electrónicos SRI (factura, NC, ND, retención…). Máquina de estados HU-F9.';
COMMENT ON COLUMN comprobante.clave_acceso IS '49 dígitos. ddMMyyyy+codDoc(2)+ruc(13)+amb(1)+serie(6)+sec(9)+ale(8)+tipoEmision(1)+dv(1).';
COMMENT ON COLUMN comprobante.numero_autorizacion IS 'Esquema offline SRI: == clave_acceso una vez autorizado.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  COMPROBANTE_DETALLE — líneas de la factura             ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE comprobante_detalle (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id                  UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    comprobante_id              UUID NOT NULL REFERENCES comprobante(id) ON DELETE CASCADE,
    orden                       INT NOT NULL CHECK (orden > 0),

    codigo_principal            VARCHAR(25) NOT NULL,           -- SRI exige codigoPrincipal
    codigo_auxiliar             VARCHAR(25),
    descripcion                 VARCHAR(300) NOT NULL,

    cantidad                    NUMERIC(18,6) NOT NULL CHECK (cantidad > 0),
    precio_unitario             NUMERIC(18,6) NOT NULL CHECK (precio_unitario >= 0),
    descuento                   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (descuento >= 0),
    -- (cantidad × precio_unitario) − descuento
    precio_total_sin_impuesto   NUMERIC(14,2) NOT NULL CHECK (precio_total_sin_impuesto >= 0),

    -- Impuesto único por línea (simplificado; SRI permite 1..N pero para Sprint 3 con uno alcanza)
    codigo_impuesto             VARCHAR(2) NOT NULL DEFAULT '2',  -- 2=IVA
    codigo_porcentaje           VARCHAR(4) NOT NULL,              -- 0=0%, 4=15%, 6=NO_OBJETO, 7=EXENTO
    tarifa                      NUMERIC(5,2) NOT NULL CHECK (tarifa >= 0),
    base_imponible              NUMERIC(14,2) NOT NULL CHECK (base_imponible >= 0),
    valor_impuesto              NUMERIC(14,2) NOT NULL CHECK (valor_impuesto >= 0),

    creado_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (comprobante_id, orden)
);
CREATE INDEX idx_comprobante_detalle_empresa ON comprobante_detalle(empresa_id);
CREATE INDEX idx_comprobante_detalle_comprobante ON comprobante_detalle(comprobante_id);

COMMENT ON TABLE comprobante_detalle IS 'Líneas de un comprobante. Subtotales se recalculan en la app antes de generar el XML.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  COMPROBANTE_EVENTO — log de transiciones               ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE comprobante_evento (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id                  UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    comprobante_id              UUID NOT NULL REFERENCES comprobante(id) ON DELETE CASCADE,
    estado_anterior             VARCHAR(20),
    estado_nuevo                VARCHAR(20) NOT NULL,
    mensaje                     TEXT,
    datos                       JSONB NOT NULL DEFAULT '{}'::jsonb,
    creado_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_comprobante_evento_comprobante ON comprobante_evento(comprobante_id, creado_at DESC);
CREATE INDEX idx_comprobante_evento_empresa ON comprobante_evento(empresa_id);

COMMENT ON TABLE comprobante_evento IS 'Bitácora de transiciones de estado del comprobante (auditoría detallada del ciclo SRI).';

-- ╔════════════════════════════════════════════════════════╗
-- ║  Triggers actualizado_at                                ║
-- ╚════════════════════════════════════════════════════════╝
CREATE OR REPLACE FUNCTION set_comprobante_actualizado_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizado_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_comprobante_actualizado_at
    BEFORE UPDATE ON comprobante
    FOR EACH ROW EXECUTE FUNCTION set_comprobante_actualizado_at();

-- ╔════════════════════════════════════════════════════════╗
-- ║  RLS — todas tenant-aware                               ║
-- ╚════════════════════════════════════════════════════════╝
ALTER TABLE comprobante ENABLE ROW LEVEL SECURITY;
ALTER TABLE comprobante FORCE ROW LEVEL SECURITY;
CREATE POLICY comprobante_tenant ON comprobante
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE comprobante_detalle ENABLE ROW LEVEL SECURITY;
ALTER TABLE comprobante_detalle FORCE ROW LEVEL SECURITY;
CREATE POLICY comprobante_detalle_tenant ON comprobante_detalle
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE comprobante_evento ENABLE ROW LEVEL SECURITY;
ALTER TABLE comprobante_evento FORCE ROW LEVEL SECURITY;
CREATE POLICY comprobante_evento_tenant ON comprobante_evento
    FOR ALL USING (empresa_id = current_empresa_id());
