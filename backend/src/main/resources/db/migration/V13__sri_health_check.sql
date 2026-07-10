-- Sprint 3 Fase B (resiliencia SRI): tabla para tracking del estado del WS SRI.
-- Un job recurring pinga periódicamente los endpoints y persiste el resultado.
-- El endpoint REST /api/v1/sri/estado lee la última fila por ambiente para que la UI
-- pueda mostrar banner "SRI no disponible" en tiempo real.
--
-- NO es tenant-aware (no tiene empresa_id, no necesita RLS): es estado global del servicio
-- SRI compartido por todos los tenants. Por eso vive en el schema público sin policies.

CREATE TABLE sri_health_check (
    id          BIGSERIAL PRIMARY KEY,
    ambiente    TEXT NOT NULL CHECK (ambiente IN ('PRUEBAS','PRODUCCION')),
    estado      TEXT NOT NULL CHECK (estado IN ('ARRIBA','CAIDO','DEGRADADO')),
    -- ARRIBA      = SRI respondió OK y latencia < 5s
    -- DEGRADADO   = respondió pero lento (>= 5s) o con códigos transitorios
    -- CAIDO       = timeout, connection refused, 5xx
    latencia_ms INTEGER,                  -- null si CAIDO
    mensaje     TEXT,                     -- detalle del error si CAIDO o DEGRADADO
    ts_check    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT sri_hc_latencia_no_negativa CHECK (latencia_ms IS NULL OR latencia_ms >= 0)
);

-- Acceso por (ambiente, ts_check DESC) para obtener el último check por ambiente
-- (que es la consulta dominante: "estado actual del SRI pruebas").
CREATE INDEX idx_sri_hc_amb_ts ON sri_health_check (ambiente, ts_check DESC);

COMMENT ON TABLE sri_health_check IS
  'Tracking del estado del WS del SRI Ecuador (pruebas y produccion). '
  'Alimentado por un job recurring que pinga cada N minutos. '
  'El endpoint /api/v1/sri/estado devuelve el ultimo check por ambiente.';
