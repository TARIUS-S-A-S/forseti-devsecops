-- Forseti — Sprint 2.5 hotfix: pausar 2FA en login sin desactivarlo + username opcional al registrarse
--
-- Cambios:
-- 1. usuario.totp_login_required (default true): permite pausar el pedido del código al loguearse
--    sin perder el secret. Util si el user quiere "descansar" del 2FA por unos días pero no perder
--    la config (no tener que escanear QR de nuevo).
-- 2. (sin cambios de schema para username — ya existe desde V8). Esto es solo recordatorio.

ALTER TABLE usuario ADD COLUMN totp_login_required BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN usuario.totp_login_required IS 'Sprint 2.5: cuando totp_activado_at IS NOT NULL Y este flag es true, login pide el código TOTP. Si el flag es false, el secret sigue guardado pero el login no pide código. Permite pausar el 2FA sin desactivarlo.';
