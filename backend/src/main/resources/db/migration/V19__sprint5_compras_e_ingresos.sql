-- Forseti — Sprint 5: Compras, ingresos manuales, adjuntos.
-- HU-F2 (compras con XML SRI autoparseado) + HU-F1 (ingreso manual histórico) +
-- HU-F3 (adjuntos PDF/XML con verificación Tika) + HU-F4 (flujo de caja) +
-- HU-F5 (exports CSV) + HU-F6 (anular, NUNCA borrar — RNF-6).
--
-- Reglas Forseti:
--   - Toda tabla tenant-aware: empresa_id NOT NULL + RLS + FORCE RLS (RNF-7).
--   - Movimientos financieros se ANULAN, no se borran (soft-delete con flag + motivo).
--   - Catálogos shared (compra_categoria) NO llevan empresa_id ni RLS — son datos.
--   - Adjuntos guardan bytes en BYTEA (≤ 10 MB) + sha256 + mime_type verificado por Tika.

-- ╔════════════════════════════════════════════════════════╗
-- ║  COMPRA_CATEGORIA — catálogo shared (datos, no código) ║
-- ╚════════════════════════════════════════════════════════╝
-- Categorías predefinidas (HU-F2). Cada empresa puede usar las que quiera; el
-- catálogo es global. Si una empresa quiere una categoría propia, en Sprint 6
-- agregamos `compra_categoria_empresa` (tenant-aware) — por ahora alcanza shared.
CREATE TABLE compra_categoria (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    codigo          VARCHAR(40) NOT NULL UNIQUE,    -- 'servicios', 'software', etc.
    nombre          VARCHAR(120) NOT NULL,
    descripcion     TEXT,
    orden           INT NOT NULL DEFAULT 0,
    activa          BOOLEAN NOT NULL DEFAULT TRUE,
    creada_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE compra_categoria IS 'Catálogo shared de categorías de compras/gastos. Editable en datos, no en código (RNF-8).';

-- Seed inicial — categorías genéricas que cualquier PYME ecuatoriana usa
INSERT INTO compra_categoria (codigo, nombre, descripcion, orden) VALUES
    ('servicios',           'Servicios profesionales',  'Asesoría, contabilidad, legal, marketing',                     10),
    ('software',            'Software y suscripciones', 'SaaS, licencias, herramientas digitales',                       20),
    ('equipos',             'Equipos y tecnología',     'Computadoras, periféricos, equipos de oficina',                 30),
    ('alquiler',            'Alquiler / arriendo',      'Oficina, coworking, espacios',                                  40),
    ('suministros',         'Suministros de oficina',   'Papelería, insumos consumibles',                                50),
    ('servicios_basicos',   'Servicios básicos',        'Luz, agua, internet, teléfono',                                 60),
    ('transporte',          'Transporte y viáticos',    'Combustible, taxis, viajes, hospedaje',                         70),
    ('publicidad',          'Publicidad y marketing',   'Ads, branding, eventos, contenido',                             80),
    ('bancarios',           'Comisiones bancarias',     'Mantenimiento, transferencias, intereses',                      90),
    ('impuestos',           'Impuestos y tasas',        'Patente, contribución SuperCIA, tasas municipales',            100),
    ('iess',                'Aportes IESS y nómina',    'Aportes patronales, salarios, décimos, utilidades',            110),
    ('mantenimiento',       'Mantenimiento y reparaciones', 'Limpieza, reparaciones, mejoras',                          120),
    ('otros',               'Otros gastos',             'Lo que no entra en otra categoría',                            999);

-- ╔════════════════════════════════════════════════════════╗
-- ║  COMPRA — cabecera (factura recibida o gasto)           ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE compra (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id                  UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,

    -- Datos del comprobante recibido
    fecha_emision               DATE NOT NULL,
    proveedor_tipo_id           VARCHAR(2) NOT NULL DEFAULT '04'
                                CHECK (proveedor_tipo_id IN ('04','05','06','08')),
    proveedor_identificacion    VARCHAR(20) NOT NULL,
    proveedor_razon_social      VARCHAR(300) NOT NULL,

    -- Tipo de documento recibido. Por simplicidad arrancamos con factura/NC/liquidación.
    tipo_documento              VARCHAR(30) NOT NULL DEFAULT 'FACTURA'
                                CHECK (tipo_documento IN ('FACTURA','NOTA_CREDITO','NOTA_DEBITO',
                                                          'LIQUIDACION_COMPRA','RECIBO','OTRO')),
    -- "001-001-000000001" o lo que diga el comprobante (acepta formato libre para recibos)
    numero_documento            VARCHAR(50) NOT NULL,
    -- Si la factura está autorizada por SRI (vino con XML), guardamos la clave_acceso
    clave_acceso                VARCHAR(49),
    fecha_autorizacion_sri      TIMESTAMPTZ,

    concepto                    VARCHAR(500) NOT NULL,
    categoria_id                UUID REFERENCES compra_categoria(id),

    -- Bases por tarifa IVA — separadas para cuadrar declaración 104
    base_iva_15                 NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (base_iva_15 >= 0),
    base_iva_0                  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (base_iva_0 >= 0),
    base_no_objeto              NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (base_no_objeto >= 0),
    base_exento                 NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (base_exento >= 0),
    valor_iva_15                NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (valor_iva_15 >= 0),
    -- Retención que YO le hago al proveedor (TARIUS retiene IR/IVA al pagar a quienes corresponde)
    retencion_ir                NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (retencion_ir >= 0),
    retencion_iva               NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (retencion_iva >= 0),
    -- Total que efectivamente vamos a pagar al proveedor
    total                       NUMERIC(14,2) NOT NULL CHECK (total >= 0),

    -- ¿Va a la declaración como gasto deducible? (default sí; algunos gastos no son deducibles)
    deducible                   BOOLEAN NOT NULL DEFAULT TRUE,

    -- Estado de pago
    estado_pago                 VARCHAR(15) NOT NULL DEFAULT 'PENDIENTE'
                                CHECK (estado_pago IN ('PENDIENTE','PAGADO','PARCIAL')),
    fecha_pago                  DATE,
    forma_pago                  VARCHAR(2),  -- mismos códigos SRI que usamos en venta

    -- XML SRI recibido (si vino vía upload) — guardado completo para auditoría 7 años
    xml_recibido                BYTEA,
    -- Origen del registro: MANUAL (alta a mano) o XML (parser del XML SRI recibido)
    origen                      VARCHAR(10) NOT NULL DEFAULT 'MANUAL'
                                CHECK (origen IN ('MANUAL','XML')),

    -- Anulación (HU-F6): NUNCA borramos. Anulada queda visible pero fuera de totales.
    anulada                     BOOLEAN NOT NULL DEFAULT FALSE,
    anulada_at                  TIMESTAMPTZ,
    anulada_por_usuario_id      UUID REFERENCES usuario(id),
    motivo_anulacion            TEXT,

    -- Auditoría
    creado_por_usuario_id       UUID REFERENCES usuario(id),
    creado_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- No se puede ingresar 2 veces la misma factura del mismo proveedor en la misma empresa
    UNIQUE (empresa_id, proveedor_identificacion, tipo_documento, numero_documento)
);
CREATE INDEX idx_compra_empresa ON compra(empresa_id);
CREATE INDEX idx_compra_fecha ON compra(empresa_id, fecha_emision DESC);
CREATE INDEX idx_compra_estado_pago ON compra(empresa_id, estado_pago) WHERE NOT anulada;
CREATE INDEX idx_compra_proveedor ON compra(empresa_id, proveedor_identificacion);
-- Para gates ② de declaración: traer bases por tarifa de un período
CREATE INDEX idx_compra_periodo ON compra(empresa_id, fecha_emision) WHERE NOT anulada;

COMMENT ON TABLE compra IS 'Cabecera de compras/gastos. HU-F2 + HU-F6 anulación. Cuadra con declaración 104.';
COMMENT ON COLUMN compra.anulada IS 'Soft-delete: queda visible para auditoría pero NO entra en totales (RNF-6).';
COMMENT ON COLUMN compra.origen IS 'MANUAL = alta a mano; XML = parser de XML SRI recibido (autollenado).';

-- ╔════════════════════════════════════════════════════════╗
-- ║  COMPRA_ADJUNTO — PDF/XML con sha256 + Tika MIME         ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE compra_adjunto (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id                  UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    compra_id                   UUID NOT NULL REFERENCES compra(id) ON DELETE CASCADE,

    nombre_original             VARCHAR(255) NOT NULL,
    mime_type_real              VARCHAR(80) NOT NULL,           -- detectado por Tika, no por extensión
    tamano_bytes                INT NOT NULL CHECK (tamano_bytes > 0 AND tamano_bytes <= 10485760),  -- 10 MB max
    sha256                      VARCHAR(64) NOT NULL,           -- hex sha-256 para detectar duplicados
    contenido                   BYTEA NOT NULL,                 -- el archivo en sí

    creado_por_usuario_id       UUID REFERENCES usuario(id),
    creado_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (compra_id, sha256)  -- mismo archivo no se sube 2 veces a la misma compra
);
CREATE INDEX idx_compra_adjunto_compra ON compra_adjunto(compra_id);
CREATE INDEX idx_compra_adjunto_empresa ON compra_adjunto(empresa_id);

COMMENT ON TABLE compra_adjunto IS 'Adjuntos (PDF/XML) de una compra. HU-F3. MIME verificado por Tika (no por extensión).';
COMMENT ON COLUMN compra_adjunto.mime_type_real IS 'MIME detectado por Apache Tika contra los bytes, no la extensión del nombre.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  INGRESO_MANUAL — ventas históricas o sin emitir SRI    ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE ingreso_manual (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id                  UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,

    fecha_emision               DATE NOT NULL,
    cliente_identificacion      VARCHAR(20),
    cliente_razon_social        VARCHAR(300) NOT NULL,
    concepto                    VARCHAR(500) NOT NULL,

    base_iva_15                 NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (base_iva_15 >= 0),
    base_iva_0                  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (base_iva_0 >= 0),
    valor_iva_15                NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (valor_iva_15 >= 0),
    -- Retención que ME hizo el cliente al pagar (HU-F7 extendido: clientes que retienen)
    retencion_recibida          NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (retencion_recibida >= 0),
    total                       NUMERIC(14,2) NOT NULL CHECK (total >= 0),

    estado_cobro                VARCHAR(15) NOT NULL DEFAULT 'PENDIENTE'
                                CHECK (estado_cobro IN ('PENDIENTE','COBRADO','PARCIAL')),
    fecha_cobro                 DATE,

    -- Anulación (HU-F6)
    anulada                     BOOLEAN NOT NULL DEFAULT FALSE,
    anulada_at                  TIMESTAMPTZ,
    anulada_por_usuario_id      UUID REFERENCES usuario(id),
    motivo_anulacion            TEXT,

    creado_por_usuario_id       UUID REFERENCES usuario(id),
    creado_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ingreso_manual_empresa ON ingreso_manual(empresa_id);
CREATE INDEX idx_ingreso_manual_fecha ON ingreso_manual(empresa_id, fecha_emision DESC);
CREATE INDEX idx_ingreso_manual_estado ON ingreso_manual(empresa_id, estado_cobro) WHERE NOT anulada;

COMMENT ON TABLE ingreso_manual IS 'Ingresos sin emitir vía SRI (históricos previos a Forseti o sin factura propia). HU-F1.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  Triggers actualizado_at                                ║
-- ╚════════════════════════════════════════════════════════╝
CREATE OR REPLACE FUNCTION set_actualizado_at_genericamente()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizado_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_compra_actualizado_at
    BEFORE UPDATE ON compra
    FOR EACH ROW EXECUTE FUNCTION set_actualizado_at_genericamente();

CREATE TRIGGER trg_ingreso_manual_actualizado_at
    BEFORE UPDATE ON ingreso_manual
    FOR EACH ROW EXECUTE FUNCTION set_actualizado_at_genericamente();

-- ╔════════════════════════════════════════════════════════╗
-- ║  RLS — todas tenant-aware (compra_categoria es shared)   ║
-- ╚════════════════════════════════════════════════════════╝
ALTER TABLE compra ENABLE ROW LEVEL SECURITY;
ALTER TABLE compra FORCE ROW LEVEL SECURITY;
CREATE POLICY compra_tenant ON compra
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE compra_adjunto ENABLE ROW LEVEL SECURITY;
ALTER TABLE compra_adjunto FORCE ROW LEVEL SECURITY;
CREATE POLICY compra_adjunto_tenant ON compra_adjunto
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE ingreso_manual ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingreso_manual FORCE ROW LEVEL SECURITY;
CREATE POLICY ingreso_manual_tenant ON ingreso_manual
    FOR ALL USING (empresa_id = current_empresa_id());
