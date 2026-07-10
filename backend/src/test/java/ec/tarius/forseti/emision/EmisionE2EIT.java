package ec.tarius.forseti.emision;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import ec.tarius.forseti.emision.dto.EmisionDtos;
import ec.tarius.forseti.emision.jobs.AutorizacionJobOperations;
import ec.tarius.forseti.emision.jobs.EnvioJobOperations;
import ec.tarius.forseti.emision.sri.RespuestaAutorizacion;
import ec.tarius.forseti.emision.sri.RespuestaRecepcion;
import ec.tarius.forseti.emision.sri.SriSoapClient;
import ec.tarius.forseti.emision.sri.TestP12Factory;
import ec.tarius.forseti.empresa.CertificadoFirmaRepository;
import ec.tarius.forseti.empresa.CertificadoService;
import ec.tarius.forseti.empresa.EmpresaRepository;
import ec.tarius.forseti.empresa.EstablecimientoRepository;
import ec.tarius.forseti.empresa.PuntoEmisionRepository;
import ec.tarius.forseti.empresa.SecuencialRepository;
import ec.tarius.forseti.empresa.domain.Empresa;
import ec.tarius.forseti.empresa.domain.Establecimiento;
import ec.tarius.forseti.empresa.domain.PuntoEmision;
import ec.tarius.forseti.empresa.domain.Secuencial;
import ec.tarius.forseti.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.jobrunr.scheduling.BackgroundJobRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integración E2E del flow Sprint 3 con Spring real, Postgres real y SriSoapClient mockeado.
 *
 * Valida que TODO el wiring real funciona end-to-end:
 *   1. EmisionService.emitirFactura: secuencial → XML → XSD → firma → persistencia
 *   2. EnvioJobOperations.procesarEnvio con SRI mockeado RECIBIDA → ENVIADA
 *   3. AutorizacionJobOperations.consultar con SRI mockeado AUTORIZADO → AUTORIZADA
 *   4. RLS activado vía TenantTransactionAdvice (sin esto, los INSERT rebotan)
 *   5. .p12 cifrado AES-256-GCM en DB y descifrado en memoria para firmar
 *
 * Setup local: el contenedor Postgres ya está corriendo en 55433 (ver SecuencialConcurrenciaIT).
 * Para correr: RUN_TESTCONTAINERS=true mvn test -Dtest=EmisionE2EIT
 */
@SpringBootTest
@ActiveProfiles("emisionit")
@DisplayName("Emisión E2E (Sprint 3 happy path)")
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true",
    disabledReason = "Requiere Postgres en localhost:55433")
class EmisionE2EIT {

    @Autowired private EmisionService emisionService;
    @Autowired private EnvioJobOperations envioOps;
    @Autowired private AutorizacionJobOperations autorizacionOps;
    @Autowired private CertificadoService certificadoService;
    @Autowired private EmpresaRepository empresaRepo;
    @Autowired private EstablecimientoRepository establecimientoRepo;
    @Autowired private PuntoEmisionRepository puntoEmisionRepo;
    @Autowired private SecuencialRepository secuencialRepo;
    @Autowired private CertificadoFirmaRepository certificadoRepo;
    @Autowired private ComprobanteRepository comprobanteRepo;
    @Autowired private ComprobanteEventoRepository eventoRepo;
    @Autowired private TransactionTemplate tx;

    @PersistenceContext private EntityManager em;

    @MockBean private SriSoapClient sriClient;

    private MockedStatic<BackgroundJobRequest> jobrunrMock;

    private UUID empresaId;
    private UUID establecimientoId;
    private UUID puntoEmisionId;

    @BeforeEach
    void setUp() {
        // BackgroundJobRequest se mockea estáticamente para no necesitar JobRunr DB schema
        jobrunrMock = Mockito.mockStatic(BackgroundJobRequest.class);

        // Limpiar todas las tablas tenant-aware + sus parents en el orden correcto
        tx.executeWithoutResult(_status -> {
            em.createNativeQuery("TRUNCATE TABLE comprobante_evento, comprobante_detalle, comprobante, " +
                "obligacion_empresa, certificado_firma, secuencial, punto_emision, establecimiento, " +
                "perfil_tributario, invitacion_empresa, usuario_empresa, sesion, auditoria, " +
                "usuario_backup_code, usuario, empresa RESTART IDENTITY CASCADE").executeUpdate();
        });

        // Crear empresa + establecimiento + punto + secuencial + cert .p12 fuera de RLS context
        // (al insertarlos como owner forseti directamente). En prod los crea EmpresaService con
        // TenantContext seteado, pero acá vamos directo a DB para fixture rápido.
        crearFixtures();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jobrunrMock.close();
    }

    @Test
    @DisplayName("Flow completo: emitir → SRI recibe → SRI autoriza → AUTORIZADA con XML guardado")
    void flow_completo_factura_autorizada() {
        // ─── 1. Emitir factura ───────────────────────────────────────────
        TenantContext.setEmpresaId(empresaId);
        EmisionDtos.EmitirFacturaRequest request = new EmisionDtos.EmitirFacturaRequest(
            establecimientoId, puntoEmisionId,
            LocalDate.of(2026, 6, 20),
            new EmisionDtos.Receptor("04", "0992345675001", "Cliente E2E Test",
                "Av. NN 100", "cliente@e2e.test", null),
            List.of(new EmisionDtos.Item(
                "PROD-001", null, "Servicio E2E",
                new java.math.BigDecimal("1.000000"),
                new java.math.BigDecimal("100.000000"),
                java.math.BigDecimal.ZERO,
                "IVA_15")),
            "01", 0);

        Comprobante c = emisionService.emitirFactura(request, "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.FIRMADA);
        assertThat(c.getXmlFirmado()).isNotNull().isNotEmpty();
        assertThat(c.getClaveAcceso()).hasSize(49).matches("\\d{49}");
        assertThat(c.getNumeroComprobante()).isEqualTo("001-001-000000001");
        assertThat(c.getImporteTotal()).isEqualByComparingTo("115.00");
        assertThat(c.getTotalIva()).isEqualByComparingTo("15.00");

        // EmisionService encoló el job de envío
        jobrunrMock.verify(() -> BackgroundJobRequest.enqueue(any(org.jobrunr.jobs.lambdas.JobRequest.class)));

        // ─── 2. SRI mock: recepción RECIBIDA ─────────────────────────────
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(c.getClaveAcceso())))
            .thenReturn(new RespuestaRecepcion(
                RespuestaRecepcion.Estado.RECIBIDA, c.getClaveAcceso(), List.of()));

        envioOps.procesarEnvio(c.getId(), "PRUEBAS");

        Comprobante despuesEnvio = comprobanteRepo.findById(c.getId()).orElseThrow();
        assertThat(despuesEnvio.getEstado()).isEqualTo(Estado.ENVIADA);
        assertThat(despuesEnvio.getIntentosEnvio()).isEqualTo(1);

        // ─── 3. SRI mock: autorización AUTORIZADO ────────────────────────
        Instant fechaAut = Instant.parse("2026-06-20T20:30:00Z");
        byte[] xmlAut = ("<autorizacion><estado>AUTORIZADO</estado>"
            + "<numeroAutorizacion>" + c.getClaveAcceso() + "</numeroAutorizacion>"
            + "</autorizacion>").getBytes();
        when(sriClient.autorizacion(eq(c.getClaveAcceso()), eq("PRUEBAS")))
            .thenReturn(new RespuestaAutorizacion(
                RespuestaAutorizacion.Estado.AUTORIZADO,
                c.getClaveAcceso(), c.getClaveAcceso(), fechaAut, "PRUEBAS",
                xmlAut, List.of()));

        autorizacionOps.consultar(c.getId(), "PRUEBAS");

        Comprobante autorizado = comprobanteRepo.findById(c.getId()).orElseThrow();
        assertThat(autorizado.getEstado()).isEqualTo(Estado.AUTORIZADA);
        assertThat(autorizado.getNumeroAutorizacion()).isEqualTo(c.getClaveAcceso());
        assertThat(autorizado.getFechaAutorizacion()).isEqualTo(fechaAut);
        assertThat(autorizado.getXmlAutorizado()).isEqualTo(xmlAut);

        // ─── 4. Verificar bitácora de eventos ────────────────────────────
        var eventos = eventoRepo.findByComprobanteIdOrderByCreadoAtAsc(c.getId());
        assertThat(eventos).hasSizeGreaterThanOrEqualTo(3);
        assertThat(eventos.get(0).getEstadoNuevo()).isEqualTo("FIRMADA");
        assertThat(eventos.stream().anyMatch(e -> "ENVIADA".equals(e.getEstadoNuevo()))).isTrue();
        assertThat(eventos.get(eventos.size() - 1).getEstadoNuevo()).isEqualTo("AUTORIZADA");

        // ─── 5. Secuencial avanzó a 2 ────────────────────────────────────
        Secuencial sec = secuencialRepo.findAll().stream()
            .filter(s -> s.getPuntoEmisionId().equals(puntoEmisionId))
            .findFirst().orElseThrow();
        assertThat(sec.getProximoNumero()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Si SRI devuelve DEVUELTA, comprobante queda DEVUELTA con mensaje legible")
    void devuelta_por_sri_termina_en_DEVUELTA() {
        TenantContext.setEmpresaId(empresaId);
        EmisionDtos.EmitirFacturaRequest req = requestSimple();
        Comprobante c = emisionService.emitirFactura(req, "PRUEBAS");

        var msg = new ec.tarius.forseti.emision.sri.MensajeSri(
            "43", "FIRMA INVALIDA", "El certificado expiró", "ERROR");
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(c.getClaveAcceso())))
            .thenReturn(new RespuestaRecepcion(
                RespuestaRecepcion.Estado.DEVUELTA, c.getClaveAcceso(), List.of(msg)));

        envioOps.procesarEnvio(c.getId(), "PRUEBAS");

        Comprobante despues = comprobanteRepo.findById(c.getId()).orElseThrow();
        assertThat(despues.getEstado()).isEqualTo(Estado.DEVUELTA);
        assertThat(despues.getMensajeSri()).contains("FIRMA INVALIDA");
        assertThat(despues.getCodigoErrorSri()).isEqualTo("43");
    }

    @Test
    @DisplayName("Cancelar comprobante FIRMADA → estado ABANDONADA + evento registrado")
    void cancelar_firmada_pasa_a_abandonada() {
        TenantContext.setEmpresaId(empresaId);
        EmisionDtos.EmitirFacturaRequest req = requestSimple();
        Comprobante c = emisionService.emitirFactura(req, "PRUEBAS");
        assertThat(c.getEstado()).isEqualTo(Estado.FIRMADA);

        Comprobante cancelado = emisionService.cancelar(c.getId(), "Error en datos del cliente");

        assertThat(cancelado.getEstado()).isEqualTo(Estado.ABANDONADA);
        Comprobante refreshed = comprobanteRepo.findById(c.getId()).orElseThrow();
        assertThat(refreshed.getEstado()).isEqualTo(Estado.ABANDONADA);

        var eventos = eventoRepo.findByComprobanteIdOrderByCreadoAtAsc(c.getId());
        var ultimo = eventos.get(eventos.size() - 1);
        assertThat(ultimo.getEstadoNuevo()).isEqualTo("ABANDONADA");
        assertThat(ultimo.getMensaje()).contains("Error en datos del cliente");
    }

    @Test
    @DisplayName("Cancelar comprobante DEVUELTA → ABANDONADA OK (alternativa a re-corregir)")
    void cancelar_devuelta_pasa_a_abandonada() {
        TenantContext.setEmpresaId(empresaId);
        EmisionDtos.EmitirFacturaRequest req = requestSimple();
        Comprobante c = emisionService.emitirFactura(req, "PRUEBAS");
        var msg = new ec.tarius.forseti.emision.sri.MensajeSri(
            "43", "FIRMA INVALIDA", null, "ERROR");
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(c.getClaveAcceso())))
            .thenReturn(new RespuestaRecepcion(
                RespuestaRecepcion.Estado.DEVUELTA, c.getClaveAcceso(), List.of(msg)));
        envioOps.procesarEnvio(c.getId(), "PRUEBAS");

        Comprobante cancelado = emisionService.cancelar(c.getId(), null);

        assertThat(cancelado.getEstado()).isEqualTo(Estado.ABANDONADA);
    }

    @Test
    @DisplayName("Cancelar AUTORIZADA falla: 'usá el portal SRI para anular'")
    void cancelar_autorizada_falla_con_mensaje_claro() {
        TenantContext.setEmpresaId(empresaId);
        EmisionDtos.EmitirFacturaRequest req = requestSimple();
        Comprobante c = emisionService.emitirFactura(req, "PRUEBAS");
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(c.getClaveAcceso())))
            .thenReturn(new RespuestaRecepcion(RespuestaRecepcion.Estado.RECIBIDA,
                c.getClaveAcceso(), List.of()));
        envioOps.procesarEnvio(c.getId(), "PRUEBAS");
        when(sriClient.autorizacion(eq(c.getClaveAcceso()), eq("PRUEBAS"))).thenReturn(
            new RespuestaAutorizacion(RespuestaAutorizacion.Estado.AUTORIZADO,
                c.getClaveAcceso(), c.getClaveAcceso(), Instant.now(), "PRUEBAS",
                new byte[]{1}, List.of()));
        autorizacionOps.consultar(c.getId(), "PRUEBAS");

        assertThat(comprobanteRepo.findById(c.getId()).orElseThrow().getEstado())
            .isEqualTo(Estado.AUTORIZADA);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> emisionService.cancelar(c.getId(), "se equivocó el cliente"))
            .hasMessageContaining("portal SRI");
    }

    @Test
    @DisplayName("Cancelar ENVIADA falla: 'esperá la respuesta SRI'")
    void cancelar_enviada_falla_con_mensaje_claro() {
        TenantContext.setEmpresaId(empresaId);
        EmisionDtos.EmitirFacturaRequest req = requestSimple();
        Comprobante c = emisionService.emitirFactura(req, "PRUEBAS");
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(c.getClaveAcceso())))
            .thenReturn(new RespuestaRecepcion(RespuestaRecepcion.Estado.RECIBIDA,
                c.getClaveAcceso(), List.of()));
        envioOps.procesarEnvio(c.getId(), "PRUEBAS");

        assertThat(comprobanteRepo.findById(c.getId()).orElseThrow().getEstado())
            .isEqualTo(Estado.ENVIADA);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> emisionService.cancelar(c.getId(), null))
            .hasMessageContaining("proceso de envío");
    }

    @Test
    @DisplayName("Si SRI está saturado, comprobante queda FIRMADA con siguiente_intento_at programado")
    void timeout_recepcion_programa_reintento() {
        TenantContext.setEmpresaId(empresaId);
        EmisionDtos.EmitirFacturaRequest req = requestSimple();
        Comprobante c = emisionService.emitirFactura(req, "PRUEBAS");

        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(c.getClaveAcceso())))
            .thenThrow(new SriSoapClient.SriIOException("SRI timeout"));

        envioOps.procesarEnvio(c.getId(), "PRUEBAS");

        Comprobante despues = comprobanteRepo.findById(c.getId()).orElseThrow();
        assertThat(despues.getEstado()).isEqualTo(Estado.FIRMADA);  // sigue listo para reintento
        assertThat(despues.getIntentosEnvio()).isEqualTo(1);
        assertThat(despues.getSiguienteIntentoAt()).isNotNull().isAfter(Instant.now());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────

    private void crearFixtures() {
        // Las tablas tenant-aware tienen FORCE RLS — necesitamos SET LOCAL app.usuario_id
        // (para INSERT empresa, requiere usuario authenticated) y app.empresa_id (para INSERT
        // establecimiento/punto/secuencial). El TransactionTemplate no dispara el aspect
        // TenantTransactionAdvice, así que seteamos manualmente con set_config.
        UUID usuarioId = UUID.randomUUID();

        // TX 1: crear usuario fake (la tabla usuario no es tenant-aware, no requiere SET).
        tx.executeWithoutResult(_status -> {
            em.createNativeQuery("INSERT INTO usuario (id, email, nombre, password_hash, email_verificado_at) " +
                "VALUES (:id, :email, 'E2E Test', '$argon2id$dummy', now())")
                .setParameter("id", usuarioId)
                .setParameter("email", "e2e+" + UUID.randomUUID() + "@test")
                .executeUpdate();
        });

        // TX 2: crear empresa + perfil + membresia (requiere SET LOCAL app.usuario_id)
        empresaId = tx.execute(_status -> {
            em.createNativeQuery("SELECT set_config('app.usuario_id', :v, true)")
                .setParameter("v", usuarioId.toString()).getSingleResult();

            Empresa e = Empresa.nueva("0992345675001", "Empresa E2E Test S.A.S.");
            e.setDireccion("Av. E2E Test 100, Quito");
            e.setTipoContribuyente("SAS");
            e.setRegimenTributario("RIMPE_EMPRENDEDOR");
            e.setPeriodicidadIva("SEMESTRAL");
            empresaRepo.save(e);

            // Insertar membresia usuario_empresa (necesaria para que el usuario "vea" la empresa)
            em.createNativeQuery("INSERT INTO usuario_empresa (usuario_id, empresa_id, rol, aceptado_at) " +
                "VALUES (:u, :e, 'DUENO', now())")
                .setParameter("u", usuarioId)
                .setParameter("e", e.getId())
                .executeUpdate();

            return e.getId();
        });

        // TX 3: crear establecimiento + punto + secuencial (requiere SET LOCAL app.empresa_id)
        tx.executeWithoutResult(_status -> {
            em.createNativeQuery("SELECT set_config('app.empresa_id', :v, true)")
                .setParameter("v", empresaId.toString()).getSingleResult();
            em.createNativeQuery("SELECT set_config('app.usuario_id', :v, true)")
                .setParameter("v", usuarioId.toString()).getSingleResult();

            Establecimiento est = Establecimiento.nuevo(empresaId, "001", "Matriz", "Av. E2E 100");
            establecimientoRepo.save(est);
            establecimientoId = est.getId();

            PuntoEmision pto = PuntoEmision.nuevo(empresaId, establecimientoId, "001", "Caja 1");
            puntoEmisionRepo.save(pto);
            puntoEmisionId = pto.getId();

            Secuencial sec = Secuencial.nuevo(empresaId, puntoEmisionId,
                Secuencial.TipoComprobante.FACTURA, Secuencial.Ambiente.PRUEBAS, 1L);
            secuencialRepo.save(sec);
        });

        // TX 4: cert .p12. CertificadoService.cargar es @Transactional → el aspect SÍ se dispara,
        // pero necesita TenantContext.empresaId seteado para que el aspect lo lea.
        TestP12Factory.P12 p12 = TestP12Factory.generar();
        TenantContext.setEmpresaId(empresaId);
        TenantContext.setUsuarioId(usuarioId);
        certificadoService.cargar(empresaId, p12.bytes(), p12.password(), usuarioId);
    }

    private EmisionDtos.EmitirFacturaRequest requestSimple() {
        return new EmisionDtos.EmitirFacturaRequest(
            establecimientoId, puntoEmisionId, LocalDate.now(),
            new EmisionDtos.Receptor("04", "0992345675001", "Cliente",
                null, null, null),
            List.of(new EmisionDtos.Item(
                "PROD-001", null, "Item",
                new java.math.BigDecimal("1.000000"),
                new java.math.BigDecimal("100.000000"),
                java.math.BigDecimal.ZERO, "IVA_15")),
            "01", 0);
    }
}
