-- Forseti — Sprint 1 fix deploy: INET → TEXT
-- Hibernate no maneja INET nativamente (sin custom type), genera VARCHAR en INSERT.
-- Cambiamos INET → TEXT — perdemos validación a nivel DB pero la app valida igual.
-- Sprint 2: si hace falta, agregar CHECK constraint con regex IPv4/IPv6.

ALTER TABLE usuario     ALTER COLUMN ultimo_login_ip TYPE TEXT;
ALTER TABLE sesion      ALTER COLUMN ip               TYPE TEXT;
ALTER TABLE auditoria   ALTER COLUMN ip               TYPE TEXT;
