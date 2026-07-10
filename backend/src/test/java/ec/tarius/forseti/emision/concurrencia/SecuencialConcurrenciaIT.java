package ec.tarius.forseti.emision.concurrencia;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate Sprint 3 ③: 100 emisiones paralelas sin saltos ni duplicados de secuencial.
 *
 * Ataca el corazón del problema de concurrencia: el SELECT FOR UPDATE sobre secuencial.
 * Sin locking pesimista, dos threads concurrentes obtienen duplicados (dos facturas con
 * 000000005) o gaps. El SRI rechaza duplicados; los gaps levantan banderas. Cero tolerancia.
 *
 * Ejecuta 100 transacciones concurrentes (25 hilos × 4 vueltas cada uno). Cada TX simula
 * el bloque crítico de EmisionService:
 *   BEGIN
 *   SELECT proximo_numero FROM secuencial WHERE ... FOR UPDATE
 *   UPDATE secuencial SET proximo_numero = proximo_numero + 1
 *   COMMIT
 *
 * Si todo funciona: deben salir EXACTAMENTE los números 1..100 (cada uno una vez)
 * y el secuencial final debe leer proximo_numero = 101.
 *
 * Setup (local): docker run -d --name forseti-test-pg -p 55433:5432 \
 *                  -e POSTGRES_USER=forseti -e POSTGRES_PASSWORD=test_pwd \
 *                  -e POSTGRES_DB=forseti_test postgres:17-alpine
 *
 * Activar con: RUN_TESTCONTAINERS=true mvn test -Dtest=SecuencialConcurrenciaIT
 */
@DisplayName("Concurrencia secuencial (gate Sprint 3 ③: 100 paralelas)")
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true",
    disabledReason = "Requiere Postgres local en puerto 55433. Ver header del test.")
class SecuencialConcurrenciaIT {

    private static final int TOTAL_EMISIONES = 100;
    private static final int THREADS = 25;

    // Conexión directa a Postgres en host:port — más simple que Testcontainers para
    // setups Windows + Docker Desktop donde la auto-detección a veces falla.
    // forseti_prod como nombre porque la migración V3 hardcodea GRANT ... ON DATABASE forseti_prod
    private static final String JDBC_URL = "jdbc:postgresql://localhost:55433/forseti_prod";
    private static final String USER = "forseti";
    private static final String PASS = "test_pwd";

    private static DataSource dataSource;
    private static UUID empresaId;
    private static UUID puntoEmisionId;
    private static UUID secuencialId;

    @BeforeAll
    static void setUp() throws Exception {
        Class.forName("org.postgresql.Driver");

        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(JDBC_URL);
        hikari.setUsername(USER);
        hikari.setPassword(PASS);
        hikari.setMaximumPoolSize(50);
        dataSource = hikari;

        // Limpiar cualquier estado previo de corridas anteriores
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
        }

        // Correr Flyway desde V1 hasta V12
        org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        // Fixtures: 1 empresa + 1 establecimiento + 1 punto + 1 secuencial inicial
        try (Connection c = dataSource.getConnection()) {
            empresaId = insertEmpresa(c);
            UUID estId = insertEstablecimiento(c, empresaId);
            puntoEmisionId = insertPuntoEmision(c, empresaId, estId);
            secuencialId = insertSecuencial(c, empresaId, puntoEmisionId);
        }
    }

    @Test
    @DisplayName("100 asignaciones concurrentes producen secuenciales 1..100 sin huecos ni duplicados")
    void cien_asignaciones_concurrentes_sin_saltos_ni_duplicados() throws Exception {
        Set<Long> numerosAsignados = Collections.synchronizedSet(new HashSet<>());
        List<Throwable> errores = Collections.synchronizedList(new java.util.ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch arranque = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(TOTAL_EMISIONES);
        AtomicInteger lanzados = new AtomicInteger();

        for (int i = 0; i < TOTAL_EMISIONES; i++) {
            pool.submit(() -> {
                try {
                    arranque.await();
                    long numero = asignarSecuencialEnTransaccion();
                    boolean nuevo = numerosAsignados.add(numero);
                    if (!nuevo) {
                        errores.add(new IllegalStateException("Secuencial duplicado: " + numero));
                    }
                    lanzados.incrementAndGet();
                } catch (Throwable t) {
                    errores.add(t);
                } finally {
                    fin.countDown();
                }
            });
        }

        arranque.countDown();
        boolean termino = fin.await(60, TimeUnit.SECONDS);
        pool.shutdown();

        // 1. Todas las asignaciones completaron
        assertThat(termino).as("Todas las asignaciones deben completar en 60s").isTrue();
        assertThat(errores).as("Sin excepciones ni duplicados").isEmpty();
        assertThat(lanzados.get()).isEqualTo(TOTAL_EMISIONES);

        // 2. Los 100 números son únicos y consecutivos 1..100
        assertThat(numerosAsignados).hasSize(TOTAL_EMISIONES);
        for (long n = 1; n <= TOTAL_EMISIONES; n++) {
            assertThat(numerosAsignados)
                .as("Falta el secuencial " + n + " (gap detectado)")
                .contains(n);
        }

        // 3. El secuencial avanzó exactamente a TOTAL_EMISIONES + 1
        long proximoFinal = leerProximoNumero();
        assertThat(proximoFinal)
            .as("El secuencial final debe ser TOTAL + 1")
            .isEqualTo(TOTAL_EMISIONES + 1);
    }

    /** Simula el bloque crítico del EmisionService.emitirFactura: SELECT FOR UPDATE + INC + COMMIT. */
    private long asignarSecuencialEnTransaccion() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT proximo_numero FROM secuencial WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, secuencialId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    long numero = rs.getLong("proximo_numero");
                    try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE secuencial SET proximo_numero = proximo_numero + 1 WHERE id = ?")) {
                        upd.setObject(1, secuencialId);
                        upd.executeUpdate();
                    }
                    c.commit();
                    return numero;
                }
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private long leerProximoNumero() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT proximo_numero FROM secuencial WHERE id = ?")) {
            ps.setObject(1, secuencialId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("proximo_numero");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────

    private static UUID insertEmpresa(Connection c) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO empresa (id, ruc, razon_social, tipo_contribuyente, regimen_tributario, periodicidad_iva) " +
            "VALUES (?, '0992345675001', 'Empresa Concurrencia Test', 'SAS', 'RIMPE_EMPRENDEDOR', 'SEMESTRAL')")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertEstablecimiento(Connection c, UUID empresaId) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO establecimiento (id, empresa_id, codigo, nombre) VALUES (?, ?, '001', 'Matriz')")) {
            ps.setObject(1, id);
            ps.setObject(2, empresaId);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertPuntoEmision(Connection c, UUID empresaId, UUID estId) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO punto_emision (id, empresa_id, establecimiento_id, codigo, descripcion) " +
            "VALUES (?, ?, ?, '001', 'Punto Caja 1')")) {
            ps.setObject(1, id);
            ps.setObject(2, empresaId);
            ps.setObject(3, estId);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insertSecuencial(Connection c, UUID empresaId, UUID puntoId) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO secuencial (id, empresa_id, punto_emision_id, tipo_comprobante, ambiente, proximo_numero) " +
            "VALUES (?, ?, ?, 'FACTURA', 'PRUEBAS', 1)")) {
            ps.setObject(1, id);
            ps.setObject(2, empresaId);
            ps.setObject(3, puntoId);
            ps.executeUpdate();
        }
        return id;
    }
}
