package ec.tarius.forseti.rls;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate del Sprint 1 ① (diferido a Sprint 2 con datos reales):
 * Validá que RLS aísla completamente los datos entre empresas (RNF-7).
 *
 * Casos:
 *   (a) SET LOCAL app.empresa_id = A → no veo filas de empresa B.
 *   (b) Sin SET LOCAL → no veo NADA en tablas con RLS.
 *   (c) Policy usuario_empresa: usuario X no ve membresías de Y (depende de current_usuario_id).
 *   (d) certificado_firma: solo veo el de mi empresa.
 *   (e) Insertar fila con empresa_id distinto al SET LOCAL: la policy lo permite (USING aplica a SELECT/UPDATE/DELETE,
 *       hay que usar WITH CHECK para INSERT — TODO Sprint 3, hoy lo gestiona la app vía TenantContext).
 *
 * NOTA: usa Flyway sobre Postgres real (Testcontainers). El rol forseti_app NO tiene BYPASSRLS;
 * el owner (forseti) tampoco, porque las tablas tienen FORCE ROW LEVEL SECURITY.
 *
 * Requiere Docker corriendo en local / CI. Si no, el test se salta con @EnabledIfEnvironmentVariable
 * (alternativa: ignorarlo en `mvn test -Dgroups=!rls`).
 */
@Testcontainers
@DisplayName("RLS multi-tenant isolation (gate Sprint 1 ①)")
@EnabledIfEnvironmentVariable(named = "RUN_RLS_TESTCONTAINERS", matches = "true",
    disabledReason = "Requiere Testcontainers + Docker daemon accesible. Correr con: RUN_RLS_TESTCONTAINERS=true mvn test -Dtest=RlsIsolationTest")
class RlsIsolationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("forseti_test")
        .withUsername("forseti")
        .withPassword("forseti_test_pwd");

    private static DataSource dataSource;
    private static UUID empresaA;
    private static UUID empresaB;
    private static UUID usuarioA;
    private static UUID usuarioB;

    @BeforeAll
    static void setUp() throws Exception {
        // Cargar driver explícitamente para tests
        Class.forName("org.postgresql.Driver");

        // Usar HikariDataSource simple para tests
        var hikari = new com.zaxxer.hikari.HikariDataSource();
        hikari.setJdbcUrl(postgres.getJdbcUrl());
        hikari.setUsername(postgres.getUsername());
        hikari.setPassword(postgres.getPassword());
        dataSource = hikari;

        // Correr Flyway sobre la DB de test
        org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        // Setear password del rol forseti_app (la migración V3 solo lo crea; password se setea por env en prod)
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE forseti_app WITH PASSWORD 'app_test_pwd'");
        }

        // Insertar fixtures: 2 empresas, 2 usuarios, membresías cruzadas
        try (Connection c = dataSource.getConnection()) {
            empresaA = insertEmpresa(c, "0992345675001", "Empresa A S.A.S.");
            empresaB = insertEmpresa(c, "1710034065001", "Empresa B Persona Natural");
            usuarioA = insertUsuario(c, "a@test.ec");
            usuarioB = insertUsuario(c, "b@test.ec");
            insertUsuarioEmpresa(c, usuarioA, empresaA);
            insertUsuarioEmpresa(c, usuarioB, empresaB);

            // Perfiles tributarios
            insertPerfilTributario(c, empresaA, "RIMPE_EMPRENDEDOR", "SEMESTRAL");
            insertPerfilTributario(c, empresaB, "RIMPE_NP", "NO_APLICA");
        }
    }

    @Test
    @DisplayName("(a) Con app.empresa_id = A, no veo perfiles de empresa B")
    void empresaA_no_ve_perfil_de_B() throws SQLException {
        try (Connection c = conectarComoApp(usuarioA, empresaA);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM perfil_tributario WHERE empresa_id = '" + empresaB + "'")) {
            rs.next();
            assertThat(rs.getInt(1))
                .as("RLS debe bloquear lectura cruzada de perfil_tributario")
                .isZero();
        }
    }

    @Test
    @DisplayName("(b) Sin SET LOCAL, current_empresa_id() es NULL y RLS bloquea todo")
    void sin_tenant_no_veo_nada() throws SQLException {
        try (Connection c = conectarComoApp(null, null);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM perfil_tributario")) {
            rs.next();
            assertThat(rs.getInt(1))
                .as("Sin tenant en contexto, no se debe ver ninguna fila de tablas tenant-aware")
                .isZero();
        }
    }

    @Test
    @DisplayName("(c) Usuario A solo ve sus propias membresías (policy usuario_empresa)")
    void usuario_solo_ve_sus_membresias() throws SQLException {
        try (Connection c = conectarComoApp(usuarioA, empresaA);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT usuario_id FROM usuario_empresa")) {
            int filas = 0;
            while (rs.next()) {
                assertThat(UUID.fromString(rs.getString(1)))
                    .as("Usuario A no debería ver membresías de otros usuarios")
                    .isEqualTo(usuarioA);
                filas++;
            }
            assertThat(filas).isPositive();
        }
    }

    @Test
    @DisplayName("(d) certificado_firma: empresa A no ve cert de empresa B")
    void certificado_aislado_por_empresa() throws SQLException {
        // Insertar un cert dummy en empresa B (como owner con set_config B)
        try (Connection c = dataSource.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.empresa_id', '" + empresaB + "', false)");
                s.execute("SELECT set_config('app.usuario_id', '" + usuarioB + "', false)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO certificado_firma (empresa_id, p12_cifrado, password_cifrada, vigente_hasta) " +
                "VALUES (?, ?, ?, now() + interval '1 year')")) {
                ps.setObject(1, empresaB);
                ps.setBytes(2, new byte[]{1, 2, 3});
                ps.setBytes(3, new byte[]{4, 5, 6});
                ps.executeUpdate();
            }
        }

        // Como usuario/empresa A, contar certs visibles → debe ser 0
        try (Connection c = conectarComoApp(usuarioA, empresaA);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM certificado_firma")) {
            rs.next();
            assertThat(rs.getInt(1))
                .as("Empresa A no debe ver el certificado de empresa B")
                .isZero();
        }
    }

    @Test
    @DisplayName("(e) Catálogo de obligaciones es shared (sin RLS) — todos lo ven")
    void catalogo_obligaciones_es_shared() throws SQLException {
        // Sin importar contexto, el catálogo se ve completo (no es tenant-aware, RNF-8)
        try (Connection c = conectarComoApp(usuarioA, empresaA);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM obligacion_catalogo WHERE activa = true")) {
            rs.next();
            assertThat(rs.getInt(1))
                .as("El catálogo precargado debe estar disponible para todos")
                .isGreaterThanOrEqualTo(20);
        }
    }

    @Test
    @DisplayName("(f) helper perfil_vigente_a(empresa, fecha) devuelve el correcto")
    void perfil_vigente_a_fecha() throws SQLException {
        try (Connection c = conectarComoApp(usuarioA, empresaA);
             PreparedStatement ps = c.prepareStatement(
                 "SELECT regimen_tributario FROM perfil_vigente_a(?::uuid, CURRENT_DATE)")) {
            ps.setObject(1, empresaA);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("RIMPE_EMPRENDEDOR");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Conecta como forseti_app (el rol runtime, NO el owner) y setea SET LOCAL para RLS.
     * Si usuarioId o empresaId son null, no setea (simula request sin sesión).
     */
    private static Connection conectarComoApp(UUID usuarioId, UUID empresaId) throws SQLException {
        Connection c = DriverManager.getConnection(
            postgres.getJdbcUrl(), "forseti_app", "app_test_pwd");
        c.setAutoCommit(false);   // SET LOCAL solo aplica dentro de TX
        try (Statement s = c.createStatement()) {
            if (usuarioId != null) {
                s.execute("SELECT set_config('app.usuario_id', '" + usuarioId + "', true)");
            }
            if (empresaId != null) {
                s.execute("SELECT set_config('app.empresa_id', '" + empresaId + "', true)");
            }
        }
        return c;
    }

    private static UUID insertEmpresa(Connection c, String ruc, String razon) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO empresa (id, ruc, razon_social, tipo_contribuyente, regimen_tributario, periodicidad_iva) " +
            "VALUES (?, ?, ?, 'SAS', 'RIMPE_EMPRENDEDOR', 'SEMESTRAL')")) {
            ps.setObject(1, id);
            ps.setString(2, ruc);
            ps.setString(3, razon);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertUsuario(Connection c, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO usuario (id, email, nombre, password_hash, email_verificado_at) " +
            "VALUES (?, ?, ?, '$argon2id$dummy', now())")) {
            ps.setObject(1, id);
            ps.setString(2, email);
            ps.setString(3, "User " + email);
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertUsuarioEmpresa(Connection c, UUID usuarioId, UUID empresaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO usuario_empresa (usuario_id, empresa_id, rol, aceptado_at) " +
            "VALUES (?, ?, 'DUENO', now())")) {
            ps.setObject(1, usuarioId);
            ps.setObject(2, empresaId);
            ps.executeUpdate();
        }
    }

    private static void insertPerfilTributario(Connection c, UUID empresaId,
                                                 String regimen, String periodicidad) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO perfil_tributario (empresa_id, vigente_desde, regimen_tributario, periodicidad_iva) " +
            "VALUES (?, CURRENT_DATE, ?, ?)")) {
            ps.setObject(1, empresaId);
            ps.setString(2, regimen);
            ps.setString(3, periodicidad);
            ps.executeUpdate();
        }
    }
}
