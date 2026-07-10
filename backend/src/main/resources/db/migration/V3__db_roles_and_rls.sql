-- Forseti — Sprint 1: separación de roles DB + activación RLS
-- forseti_app: el rol que la app usa (sin BYPASSRLS, sin SUPERUSER)
-- forseti_migrate: el rol que Flyway usa (owner del schema, puede crear tablas)
--
-- IMPORTANTE: este script asume que la conexión actual es la del owner (forseti).
-- En prod, el flujo es:
-- 1. Crear rol forseti_app con limitaciones
-- 2. GRANT SELECT/INSERT/UPDATE/DELETE en tablas existentes y futuras
-- 3. Activar RLS en las tablas tenant-aware
-- 4. La app conecta como forseti_app, Flyway sigue conectando como forseti (owner)

-- ╔════════════════════════════════════════════════════════╗
-- ║  Rol forseti_app — runtime de la app                    ║
-- ╚════════════════════════════════════════════════════════╝
DO $$
BEGIN
    -- Crear rol si no existe
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forseti_app') THEN
        CREATE ROLE forseti_app WITH LOGIN
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
            NOREPLICATION;
        -- La password se setea via ALTER ROLE en script de provisión
        -- (no hardcodear acá; la app la lee de /etc/forseti/db.env)
        RAISE NOTICE 'Rol forseti_app creado. La password debe setearse externamente.';
    END IF;
END
$$;

-- Permisos básicos
GRANT CONNECT ON DATABASE forseti_prod TO forseti_app;
GRANT USAGE ON SCHEMA public TO forseti_app;

-- GRANT en tablas existentes
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO forseti_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO forseti_app;

-- GRANT en futuras tablas (Flyway crea como owner = forseti, hay que extender)
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO forseti_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO forseti_app;

-- Funciones (gen_random_uuid, current_setting, etc.)
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO forseti_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO forseti_app;
