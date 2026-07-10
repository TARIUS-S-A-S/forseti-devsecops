-- Forseti — Sprint 2: perfil tributario con vigencias + establecimientos/secuenciales + certificado .p12 + catálogo de obligaciones
--
-- Reglas:
--   - Toda tabla tenant-aware lleva empresa_id NOT NULL + RLS + FORCE RLS (RNF-7).
--   - Cero datos quemados en código: el catálogo de obligaciones vive en datos (RNF-8); la activación es por empresa.
--   - El .p12 se guarda cifrado en columna BYTEA (AES-256-GCM aplicado en la app antes de persistir, ADR-6).
--     La llave maestra vive fuera de DB (env FORSETI_MASTER_KEY). Acá solo hay opaque blobs.
--   - perfil_tributario soporta vigencias: cambiar régimen NO pisa historial — se crea un registro nuevo y se cierra el anterior.

-- ╔════════════════════════════════════════════════════════╗
-- ║  Agregar agente_retencion a empresa                     ║
-- ╚════════════════════════════════════════════════════════╝
-- (El régimen/periodicidad sigue viviendo en empresa para el snapshot vigente
--  + en perfil_tributario para el historial. Empresa.regimen es la "vista actual".)
ALTER TABLE empresa ADD COLUMN agente_retencion BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE empresa ADD COLUMN logo_url           TEXT;

-- ╔════════════════════════════════════════════════════════╗
-- ║  PERFIL_TRIBUTARIO — historial con vigencias            ║
-- ╚════════════════════════════════════════════════════════╝
-- Una empresa puede cambiar de régimen (RIMPE NP → emprendedor → general)
-- o de periodicidad (semestral → mensual al pasar umbral). El perfil_vigente_a(fecha)
-- devuelve el correcto para la fecha consultada (importante para declarar periodos pasados).
CREATE TABLE perfil_tributario (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id              UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    -- Vigencia: vigente_desde inclusive, vigente_hasta exclusivo (NULL = aún vigente)
    vigente_desde           DATE NOT NULL,
    vigente_hasta           DATE,
    -- Valores tributarios del perfil
    regimen_tributario      VARCHAR(30) NOT NULL
                            CHECK (regimen_tributario IN ('RIMPE_NP', 'RIMPE_EMPRENDEDOR', 'GENERAL')),
    periodicidad_iva        VARCHAR(20) NOT NULL
                            CHECK (periodicidad_iva IN ('MENSUAL', 'SEMESTRAL', 'NO_APLICA')),
    obligado_contabilidad   BOOLEAN NOT NULL DEFAULT false,
    agente_retencion        BOOLEAN NOT NULL DEFAULT false,
    -- Auditoría del cambio
    motivo_cambio           TEXT,           -- "paso a régimen general por ingresos > umbral"
    creado_por_usuario_id   UUID REFERENCES usuario(id),
    creado_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT perfil_vigencia_chk CHECK (vigente_hasta IS NULL OR vigente_hasta > vigente_desde)
);
CREATE INDEX idx_perfil_tributario_empresa ON perfil_tributario(empresa_id);
CREATE INDEX idx_perfil_tributario_vigente ON perfil_tributario(empresa_id, vigente_desde DESC);
-- Solo una vigencia abierta por empresa
CREATE UNIQUE INDEX idx_perfil_tributario_una_vigente ON perfil_tributario(empresa_id)
    WHERE vigente_hasta IS NULL;

COMMENT ON TABLE perfil_tributario IS 'Vigencias del perfil — cambia régimen sin perder historial (HU-F16, ADR-7).';

-- ╔════════════════════════════════════════════════════════╗
-- ║  ESTABLECIMIENTO + PUNTO_EMISION + SECUENCIAL           ║
-- ╚════════════════════════════════════════════════════════╝
-- Estructura SRI: una empresa tiene 1..N establecimientos (códigos 001, 002…),
-- cada uno con 1..N puntos de emisión (001, 002…), cada punto con su secuencial
-- por tipo de comprobante (factura, NC, ND, retención, guía).
CREATE TABLE establecimiento (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id              UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    codigo                  VARCHAR(3) NOT NULL CHECK (codigo ~ '^[0-9]{3}$'),
    nombre                  VARCHAR(200),
    direccion               TEXT,
    activo                  BOOLEAN NOT NULL DEFAULT true,
    creado_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (empresa_id, codigo)
);
CREATE INDEX idx_establecimiento_empresa ON establecimiento(empresa_id);

CREATE TABLE punto_emision (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id              UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    establecimiento_id      UUID NOT NULL REFERENCES establecimiento(id) ON DELETE CASCADE,
    codigo                  VARCHAR(3) NOT NULL CHECK (codigo ~ '^[0-9]{3}$'),
    descripcion             VARCHAR(200),
    activo                  BOOLEAN NOT NULL DEFAULT true,
    creado_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (establecimiento_id, codigo)
);
CREATE INDEX idx_punto_emision_empresa ON punto_emision(empresa_id);
CREATE INDEX idx_punto_emision_est ON punto_emision(establecimiento_id);

-- Secuencial: contador por (punto_emision, tipo_comprobante).
-- IMPORTANTE: la asignación es transaccional (SELECT … FOR UPDATE en el repo)
-- para evitar saltos / duplicados bajo concurrencia. Gate Sprint 3.
CREATE TABLE secuencial (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id              UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    punto_emision_id        UUID NOT NULL REFERENCES punto_emision(id) ON DELETE CASCADE,
    -- Tipo de comprobante SRI: FACTURA, NOTA_CREDITO, NOTA_DEBITO, RETENCION, GUIA_REMISION, LIQUIDACION_COMPRA
    tipo_comprobante        VARCHAR(30) NOT NULL,
    -- Próximo número a usar (9 dígitos, formato SRI 001-001-000000001)
    proximo_numero          BIGINT NOT NULL DEFAULT 1 CHECK (proximo_numero > 0),
    -- Ambiente: PRUEBAS, PRODUCCION (mismo punto, distintos rangos)
    ambiente                VARCHAR(15) NOT NULL DEFAULT 'PRUEBAS'
                            CHECK (ambiente IN ('PRUEBAS', 'PRODUCCION')),
    actualizado_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (punto_emision_id, tipo_comprobante, ambiente)
);
CREATE INDEX idx_secuencial_empresa ON secuencial(empresa_id);
CREATE INDEX idx_secuencial_punto ON secuencial(punto_emision_id);

COMMENT ON TABLE secuencial IS 'Contador transaccional por punto/tipo/ambiente. FOR UPDATE al asignar (sin saltos).';

-- ╔════════════════════════════════════════════════════════╗
-- ║  CERTIFICADO_FIRMA — .p12 cifrado (ADR-6, RL-6)        ║
-- ╚════════════════════════════════════════════════════════╝
-- Custodia del certificado de firma electrónica:
--   - p12_cifrado: bytes del archivo .p12 cifrados con AES-256-GCM (llave fuera de DB)
--   - password_cifrada: la contraseña del .p12, también cifrada con AES-256-GCM
--   - Solo el servicio firmador descifra al momento de firmar; NUNCA viaja al front
--   - Nunca se loguea; no expone ningún endpoint que devuelva el blob plano
CREATE TABLE certificado_firma (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id              UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    -- Blobs cifrados (nonce || ciphertext || tag) — opaque para la DB
    p12_cifrado             BYTEA NOT NULL,
    password_cifrada        BYTEA NOT NULL,
    -- Metadata del certificado (extraída al cargar para mostrar UI)
    sujeto_cn               VARCHAR(300),       -- "Hernán Mateo Jurado Mantilla"
    emisor_cn               VARCHAR(300),       -- "AC SECURITY DATA…"
    numero_serie            VARCHAR(100),
    vigente_desde           TIMESTAMPTZ,
    vigente_hasta           TIMESTAMPTZ NOT NULL,
    -- Estado dentro de Forseti
    activo                  BOOLEAN NOT NULL DEFAULT true,
    cargado_por_usuario_id  UUID REFERENCES usuario(id),
    cargado_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_certificado_empresa ON certificado_firma(empresa_id);
CREATE INDEX idx_certificado_vigente ON certificado_firma(empresa_id, vigente_hasta) WHERE activo;
-- Solo un certificado activo por empresa (partial unique index)
CREATE UNIQUE INDEX idx_certificado_un_activo ON certificado_firma(empresa_id) WHERE activo;

COMMENT ON TABLE certificado_firma IS 'Custodia .p12: blob + password cifrados AES-256-GCM (ADR-6, RL-6). Llave maestra fuera de DB.';
COMMENT ON COLUMN certificado_firma.p12_cifrado IS 'AES-256-GCM (nonce 12B || ciphertext || tag 16B). Llave maestra en env FORSETI_MASTER_KEY.';
COMMENT ON COLUMN certificado_firma.password_cifrada IS 'Contraseña del .p12 cifrada. Solo el firmador descifra. NUNCA al front, NUNCA en logs.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  OBLIGACION_CATALOGO — datos cargados con seed (RNF-8)  ║
-- ╚════════════════════════════════════════════════════════╝
-- Catálogo COMPLETO de obligaciones de Ecuador. NO tenant-aware (es shared).
-- Cada empresa activa las suyas según su perfil en obligacion_empresa.
-- Actualizar este catálogo cuando la ley cambie: solo ALTER/INSERT, sin redeploy de código.
CREATE TABLE obligacion_catalogo (
    codigo                  VARCHAR(40) PRIMARY KEY,            -- "SRI_IVA_MENSUAL", "SRI_RENTA_RIMPE", "SUPERCIA_EEFF"...
    nombre                  VARCHAR(200) NOT NULL,
    descripcion             TEXT,
    -- Categoría: SRI_DECLARACION, SRI_ANEXO, SUPERCIA, MUNICIPIO, IESS_MDT, INTERNA
    categoria               VARCHAR(30) NOT NULL,
    -- Periodicidad: MENSUAL, SEMESTRAL, ANUAL, UNICA, EVENTUAL
    periodicidad            VARCHAR(20) NOT NULL,
    -- Regla de fecha (texto humano, ej: "9.º dígito RUC; mes siguiente"). El generador la interpreta en Sprint 7+.
    regla_fecha             TEXT,
    -- A quién aplica: condiciones declarativas (JSON) — los generadores las evalúan contra el perfil
    -- Ej: {"regimen": ["RIMPE_EMPRENDEDOR","GENERAL"], "periodicidad_iva": ["MENSUAL"]}
    aplica_si               JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- ¿Es bloqueante? (multa significativa si se incumple)
    bloqueante              BOOLEAN NOT NULL DEFAULT true,
    -- Días de alerta antes del vencimiento
    alerta_dias             INT[] NOT NULL DEFAULT ARRAY[30,15,5],
    -- Orden de presentación en la UI
    orden                   INT NOT NULL DEFAULT 100,
    activa                  BOOLEAN NOT NULL DEFAULT true,
    creada_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizada_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_obligacion_categoria ON obligacion_catalogo(categoria);
CREATE INDEX idx_obligacion_orden ON obligacion_catalogo(orden);

COMMENT ON TABLE obligacion_catalogo IS 'Catálogo COMPLETO de obligaciones EC. Datos, no código (RNF-8). Se carga en V7.';

-- ╔════════════════════════════════════════════════════════╗
-- ║  OBLIGACION_EMPRESA — las que cada empresa activa       ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TABLE obligacion_empresa (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id              UUID NOT NULL REFERENCES empresa(id) ON DELETE CASCADE,
    obligacion_codigo       VARCHAR(40) NOT NULL REFERENCES obligacion_catalogo(codigo),
    -- Estado en el calendario de la empresa
    activa                  BOOLEAN NOT NULL DEFAULT true,
    -- Configuración opcional (override del catálogo): días de alerta custom, RUC del establecimiento que declara, etc.
    config                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    activada_por_usuario_id UUID REFERENCES usuario(id),
    activada_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizada_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (empresa_id, obligacion_codigo)
);
CREATE INDEX idx_obligacion_empresa_empresa ON obligacion_empresa(empresa_id);

-- ╔════════════════════════════════════════════════════════╗
-- ║  Triggers actualizado_at                                ║
-- ╚════════════════════════════════════════════════════════╝
CREATE TRIGGER trg_establecimiento_actualizado_at
    BEFORE UPDATE ON establecimiento
    FOR EACH ROW EXECUTE FUNCTION set_actualizado_at();

CREATE TRIGGER trg_punto_emision_actualizado_at
    BEFORE UPDATE ON punto_emision
    FOR EACH ROW EXECUTE FUNCTION set_actualizado_at();

CREATE OR REPLACE FUNCTION set_secuencial_actualizado_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizado_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_secuencial_actualizado_at
    BEFORE UPDATE ON secuencial
    FOR EACH ROW EXECUTE FUNCTION set_secuencial_actualizado_at();

CREATE OR REPLACE FUNCTION set_obligacion_empresa_actualizada_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizada_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_obligacion_empresa_actualizada_at
    BEFORE UPDATE ON obligacion_empresa
    FOR EACH ROW EXECUTE FUNCTION set_obligacion_empresa_actualizada_at();

CREATE OR REPLACE FUNCTION set_obligacion_catalogo_actualizada_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.actualizada_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_obligacion_catalogo_actualizada_at
    BEFORE UPDATE ON obligacion_catalogo
    FOR EACH ROW EXECUTE FUNCTION set_obligacion_catalogo_actualizada_at();

-- ╔════════════════════════════════════════════════════════╗
-- ║  RLS — Row Level Security en todas las nuevas tablas    ║
-- ║       tenant-aware (RNF-7)                              ║
-- ╚════════════════════════════════════════════════════════╝
-- Patrón homogéneo: USING empresa_id = current_empresa_id()
-- FORCE RLS afecta también al owner (no hay bypass).

ALTER TABLE perfil_tributario ENABLE ROW LEVEL SECURITY;
ALTER TABLE perfil_tributario FORCE ROW LEVEL SECURITY;
CREATE POLICY perfil_tributario_tenant ON perfil_tributario
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE establecimiento ENABLE ROW LEVEL SECURITY;
ALTER TABLE establecimiento FORCE ROW LEVEL SECURITY;
CREATE POLICY establecimiento_tenant ON establecimiento
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE punto_emision ENABLE ROW LEVEL SECURITY;
ALTER TABLE punto_emision FORCE ROW LEVEL SECURITY;
CREATE POLICY punto_emision_tenant ON punto_emision
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE secuencial ENABLE ROW LEVEL SECURITY;
ALTER TABLE secuencial FORCE ROW LEVEL SECURITY;
CREATE POLICY secuencial_tenant ON secuencial
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE certificado_firma ENABLE ROW LEVEL SECURITY;
ALTER TABLE certificado_firma FORCE ROW LEVEL SECURITY;
CREATE POLICY certificado_firma_tenant ON certificado_firma
    FOR ALL USING (empresa_id = current_empresa_id());

ALTER TABLE obligacion_empresa ENABLE ROW LEVEL SECURITY;
ALTER TABLE obligacion_empresa FORCE ROW LEVEL SECURITY;
CREATE POLICY obligacion_empresa_tenant ON obligacion_empresa
    FOR ALL USING (empresa_id = current_empresa_id());

-- obligacion_catalogo NO tiene RLS — es shared (datos de Ecuador, mismos para todos).

-- ╔════════════════════════════════════════════════════════╗
-- ║  Helper: perfil_vigente_a(empresa, fecha)               ║
-- ╚════════════════════════════════════════════════════════╝
-- Devuelve el registro de perfil_tributario vigente para una empresa a la fecha dada.
-- Usado en declaraciones de periodos pasados (Sprint 7+).
CREATE OR REPLACE FUNCTION perfil_vigente_a(p_empresa UUID, p_fecha DATE)
RETURNS TABLE (
    id                      UUID,
    regimen_tributario      VARCHAR,
    periodicidad_iva        VARCHAR,
    obligado_contabilidad   BOOLEAN,
    agente_retencion        BOOLEAN
) AS $$
    SELECT pt.id, pt.regimen_tributario, pt.periodicidad_iva, pt.obligado_contabilidad, pt.agente_retencion
    FROM perfil_tributario pt
    WHERE pt.empresa_id = p_empresa
      AND pt.vigente_desde <= p_fecha
      AND (pt.vigente_hasta IS NULL OR pt.vigente_hasta > p_fecha)
    ORDER BY pt.vigente_desde DESC
    LIMIT 1;
$$ LANGUAGE sql STABLE;

GRANT EXECUTE ON FUNCTION perfil_vigente_a(UUID, DATE) TO forseti_app;

-- Default privileges ya están seteados en V3, así que forseti_app ya tiene SELECT/INSERT/UPDATE/DELETE en estas tablas.
