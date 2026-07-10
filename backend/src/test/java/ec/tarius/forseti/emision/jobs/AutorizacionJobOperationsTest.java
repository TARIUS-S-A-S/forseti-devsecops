package ec.tarius.forseti.emision.jobs;

import ec.tarius.forseti.emision.ComprobanteEventoRepository;
import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import ec.tarius.forseti.emision.domain.ComprobanteEvento;
import ec.tarius.forseti.emision.sri.MensajeSri;
import ec.tarius.forseti.emision.sri.RespuestaAutorizacion;
import ec.tarius.forseti.emision.sri.SriSoapClient;
import org.jobrunr.scheduling.BackgroundJobRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutorizacionJobOperationsTest {

    private ComprobanteRepository comprobanteRepo;
    private ComprobanteEventoRepository eventoRepo;
    private SriSoapClient sriClient;
    private AutorizacionJobOperations ops;
    private MockedStatic<BackgroundJobRequest> jobrunr;

    private static final String CLAVE_ACCESO = "2006202601179323597600110010010000000071234567819";

    @BeforeEach
    void setUp() {
        comprobanteRepo = mock(ComprobanteRepository.class);
        eventoRepo = mock(ComprobanteEventoRepository.class);
        sriClient = mock(SriSoapClient.class);
        // Sprint 4: nuevas deps para enviar correo al autorizar.
        var detalleRepo = mock(ec.tarius.forseti.emision.ComprobanteDetalleRepository.class);
        var empresaRepo = mock(ec.tarius.forseti.empresa.EmpresaRepository.class);
        var rideRenderer = mock(ec.tarius.forseti.emision.pdf.RideRenderer.class);
        var emailService = mock(ec.tarius.forseti.shared.email.EmailService.class);
        ops = new AutorizacionJobOperations(comprobanteRepo, detalleRepo, eventoRepo,
            empresaRepo, sriClient, rideRenderer, emailService);
        jobrunr = mockStatic(BackgroundJobRequest.class);
    }

    @AfterEach
    void tearDown() {
        jobrunr.close();
    }

    @Test
    void AUTORIZADO_marca_AUTORIZADA_y_guarda_xml_y_numero() {
        Comprobante c = comprobanteEnviado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        Instant fechaAut = Instant.parse("2026-06-20T20:30:45Z");
        byte[] xmlAut = "<autorizacion>...</autorizacion>".getBytes();
        when(sriClient.autorizacion(CLAVE_ACCESO, "PRUEBAS")).thenReturn(
            new RespuestaAutorizacion(RespuestaAutorizacion.Estado.AUTORIZADO,
                CLAVE_ACCESO, CLAVE_ACCESO, fechaAut, "PRUEBAS", xmlAut, List.of()));

        ops.consultar(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.AUTORIZADA);
        assertThat(c.getXmlAutorizado()).isEqualTo(xmlAut);
        assertThat(c.getNumeroAutorizacion()).isEqualTo(CLAVE_ACCESO);
        assertThat(c.getFechaAutorizacion()).isEqualTo(fechaAut);
        assertThat(c.getMensajeSri()).contains("Autorizado");

        verify(comprobanteRepo).save(c);
        verify(eventoRepo).save(any(ComprobanteEvento.class));
        // NO se encola nada — autorizado es terminal
        jobrunr.verifyNoInteractions();
    }

    @Test
    void NO_AUTORIZADO_marca_NO_AUTORIZADA_con_mensaje() {
        Comprobante c = comprobanteEnviado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        MensajeSri msg = new MensajeSri("70", "CLAVE ACCESO REGISTRADA",
            "Ya existe un comprobante con esta clave", "ERROR");
        when(sriClient.autorizacion(CLAVE_ACCESO, "PRUEBAS")).thenReturn(
            new RespuestaAutorizacion(RespuestaAutorizacion.Estado.NO_AUTORIZADO,
                CLAVE_ACCESO, null, null, "PRUEBAS", null, List.of(msg)));

        ops.consultar(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.NO_AUTORIZADA);
        assertThat(c.getMensajeSri()).contains("CLAVE ACCESO REGISTRADA");
        assertThat(c.getCodigoErrorSri()).isEqualTo("70");

        verify(comprobanteRepo).save(c);
        verify(eventoRepo).save(any(ComprobanteEvento.class));
        jobrunr.verifyNoInteractions();
    }

    @Test
    void EN_PROCESAMIENTO_marca_EN_PROCESO_y_encola_siguiente_consulta() {
        Comprobante c = comprobanteEnviado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.autorizacion(CLAVE_ACCESO, "PRUEBAS")).thenReturn(
            new RespuestaAutorizacion(RespuestaAutorizacion.Estado.EN_PROCESAMIENTO,
                CLAVE_ACCESO, null, null, "PRUEBAS", null, List.of()));

        ops.consultar(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.EN_PROCESO);
        // Fixture comprobanteEnviado() ya hizo marcarEnviado() = 1 intento; el poll fallido suma 1 más → 2
        assertThat(c.getIntentosEnvio()).isEqualTo(2);
        assertThat(c.getSiguienteIntentoAt()).isNotNull();

        jobrunr.verify(() -> BackgroundJobRequest.schedule(any(Instant.class),
            any(ConsultarAutorizacionRequest.class)));
    }

    @Test
    void NO_ENCONTRADO_encola_consulta_porque_sri_aun_no_procesa() {
        Comprobante c = comprobanteEnviado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.autorizacion(CLAVE_ACCESO, "PRUEBAS")).thenReturn(
            new RespuestaAutorizacion(RespuestaAutorizacion.Estado.NO_ENCONTRADO,
                CLAVE_ACCESO, null, null, null, null, List.of()));

        ops.consultar(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.EN_PROCESO);  // ENVIADA → EN_PROCESO porque
        jobrunr.verify(() -> BackgroundJobRequest.schedule(any(Instant.class),
            any(ConsultarAutorizacionRequest.class)));
    }

    @Test
    void IOException_durante_consulta_programa_reintento() {
        Comprobante c = comprobanteEnviado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.autorizacion(CLAVE_ACCESO, "PRUEBAS"))
            .thenThrow(new SriSoapClient.SriIOException("timeout"));

        ops.consultar(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.ENVIADA);  // sigue ENVIADA, listo para reintento
        assertThat(c.getIntentosEnvio()).isEqualTo(2);  // fixture=1 + reintento=1
        jobrunr.verify(() -> BackgroundJobRequest.schedule(any(Instant.class),
            any(ConsultarAutorizacionRequest.class)));
    }

    @Test
    void ProtocolException_marca_ABANDONADA() {
        Comprobante c = comprobanteEnviado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.autorizacion(CLAVE_ACCESO, "PRUEBAS"))
            .thenThrow(new SriSoapClient.SriProtocolException("SOAP roto"));

        ops.consultar(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.ABANDONADA);
        jobrunr.verifyNoInteractions();
    }

    @Test
    void consulta_abandona_despues_de_max_polls_en_proceso() {
        Comprobante c = comprobanteEnviado();
        for (int i = 0; i < Backoff.MAX_INTENTOS; i++) {
            c.registrarIntentoFallido(Instant.now());
        }
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.autorizacion(CLAVE_ACCESO, "PRUEBAS")).thenReturn(
            new RespuestaAutorizacion(RespuestaAutorizacion.Estado.EN_PROCESAMIENTO,
                CLAVE_ACCESO, null, null, "PRUEBAS", null, List.of()));

        ops.consultar(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.ABANDONADA);
        jobrunr.verifyNoInteractions();
    }

    @Test
    void comprobante_no_en_ENVIADA_skip_sin_llamar_sri() {
        Comprobante c = comprobanteEnviado();
        c.marcarAutorizado(new byte[]{1}, CLAVE_ACCESO, Instant.now());  // ya autorizado
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));

        ops.consultar(c.getId(), "PRUEBAS");

        verifyNoInteractions(sriClient, eventoRepo);
        verify(comprobanteRepo, never()).save(any());
    }

    @Test
    void comprobante_no_encontrado_no_lanza() {
        when(comprobanteRepo.findById(any())).thenReturn(Optional.empty());
        ops.consultar(UUID.randomUUID(), "PRUEBAS");
        verifyNoInteractions(sriClient, eventoRepo);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────

    private Comprobante comprobanteEnviado() {
        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRUEBAS", 1L, "001-001-000000001",
            CLAVE_ACCESO, LocalDate.now());
        c.receptor("04", "1790012345001", "Cliente", null, null, null);
        c.formaPago("01", 0);
        c.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));
        c.marcarFirmado(new byte[]{1, 2, 3});
        c.marcarEnviado();
        return c;
    }
}
