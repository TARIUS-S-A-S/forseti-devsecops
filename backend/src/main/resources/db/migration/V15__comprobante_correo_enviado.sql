-- Sprint 4 item 3 — Correo al cliente: flag de idempotencia.
-- Cuando el job de autorización transiciona a AUTORIZADA, dispara un correo al receptor
-- con XML+RIDE adjuntos (si tiene email). Este campo registra la fecha de envío para
-- evitar reenvíos accidentales si el job se reintenta o si alguien fuerza un re-check.

ALTER TABLE comprobante
    ADD COLUMN correo_enviado_at TIMESTAMPTZ;

COMMENT ON COLUMN comprobante.correo_enviado_at IS
    'Fecha/hora en que se envio el correo al receptor con XML+RIDE adjuntos. '
    'NULL si todavia no se envio o si el receptor no tiene email. Idempotencia.';
