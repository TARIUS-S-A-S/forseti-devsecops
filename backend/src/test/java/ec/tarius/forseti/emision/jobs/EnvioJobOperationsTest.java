package ec.tarius.forseti.emision.jobs;

import ec.tarius.forseti.emision.ComprobanteEventoRepository;
import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import ec.tarius.forseti.emision.domain.ComprobanteEvento;
import ec.tarius.forseti.emision.sri.MensajeSri;
import ec.tarius.forseti.emision.sri.RespuestaRecepcion;
import ec.tarius.forseti.emision.sri.SriSoapClient;
import org.jobrunr.scheduling.BackgroundJobRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests del job de envío al SRI (recepción).
 * Cubre los 4 escenarios del gate Sprint 3 ②: AUTORIZADA (recibida), DEVUELTA, IO (retry),
 * SOAP protocol (abandonar). Más casos borde: comprobante no encontrado, estado terminal.
 */
class EnvioJobOperationsTest {

    private ComprobanteRepository comprobanteRepo;
    private ComprobanteEventoRepository eventoRepo;
    private SriSoapClient sriClient;
    private EnvioJobOperations ops;
    private MockedStatic<BackgroundJobRequest> jobrunr;

    private static final String CLAVE_ACCESO = "2006202601179323597600110010010000000071234567819";

    @BeforeEach
    void setUp() {
        comprobanteRepo = mock(ComprobanteRepository.class);
        eventoRepo = mock(ComprobanteEventoRepository.class);
        sriClient = mock(SriSoapClient.class);
        ops = new EnvioJobOperations(comprobanteRepo, eventoRepo, sriClient);
        jobrunr = mockStatic(BackgroundJobRequest.class);
    }

    @AfterEach
    void tearDown() {
        jobrunr.close();
    }

    @Test
    void RECIBIDA_marca_ENVIADA_y_encola_consulta_autorizacion() {
        Comprobante c = comprobanteFirmado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(CLAVE_ACCESO)))
            .thenReturn(new RespuestaRecepcion(RespuestaRecepcion.Estado.RECIBIDA, CLAVE_ACCESO, List.of()));

        ops.procesarEnvio(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.ENVIADA);
        verify(comprobanteRepo).save(c);

        ArgumentCaptor<ComprobanteEvento> ev = ArgumentCaptor.forClass(ComprobanteEvento.class);
        verify(eventoRepo).save(ev.capture());
        assertThat(ev.getValue().getEstadoNuevo()).isEqualTo("ENVIADA");
        assertThat(ev.getValue().getMensaje()).contains("RECIBIDA");

        // Encoló autorización con delay
        jobrunr.verify(() -> BackgroundJobRequest.schedule(any(java.time.Instant.class),
            any(ConsultarAutorizacionRequest.class)));
    }

    @Test
    void DEVUELTA_marca_DEVUELTA_con_mensajes_y_NO_encola_autorizacion() {
        Comprobante c = comprobanteFirmado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        MensajeSri msg = new MensajeSri("43", "FIRMA INVALIDA",
            "Certificado no corresponde al RUC", "ERROR");
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(CLAVE_ACCESO)))
            .thenReturn(new RespuestaRecepcion(RespuestaRecepcion.Estado.DEVUELTA, CLAVE_ACCESO, List.of(msg)));

        ops.procesarEnvio(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.DEVUELTA);
        assertThat(c.getMensajeSri()).contains("FIRMA INVALIDA");
        assertThat(c.getCodigoErrorSri()).isEqualTo("43");

        verify(comprobanteRepo).save(c);
        verify(eventoRepo).save(any(ComprobanteEvento.class));

        // NO se encola autorización porque fue rechazada en recepción
        jobrunr.verifyNoInteractions();
    }

    @Test
    void IOException_programa_reintento_con_backoff_y_no_cambia_estado() {
        Comprobante c = comprobanteFirmado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(CLAVE_ACCESO)))
            .thenThrow(new SriSoapClient.SriIOException("SRI timeout"));

        ops.procesarEnvio(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.FIRMADA);  // sigue FIRMADA, listo para retry
        assertThat(c.getIntentosEnvio()).isEqualTo(1);
        assertThat(c.getSiguienteIntentoAt()).isNotNull();
        verify(comprobanteRepo).save(c);

        // Encoló reintento del mismo envío
        jobrunr.verify(() -> BackgroundJobRequest.schedule(any(java.time.Instant.class),
            any(EnviarComprobanteRequest.class)));
    }

    @Test
    void ProtocolException_marca_ABANDONADA_y_no_reintenta() {
        Comprobante c = comprobanteFirmado();
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(CLAVE_ACCESO)))
            .thenThrow(new SriSoapClient.SriProtocolException("SOAP malformado"));

        ops.procesarEnvio(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.ABANDONADA);
        verify(comprobanteRepo).save(c);

        // NO encola nada — protocol error es permanente
        jobrunr.verifyNoInteractions();
    }

    @Test
    void reintento_abandona_despues_de_max_intentos() {
        Comprobante c = comprobanteFirmado();
        for (int i = 0; i < Backoff.MAX_INTENTOS; i++) {
            c.registrarIntentoFallido(java.time.Instant.now());
        }
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(CLAVE_ACCESO)))
            .thenThrow(new SriSoapClient.SriIOException("SRI timeout"));

        ops.procesarEnvio(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.ABANDONADA);
        // No encola otro reintento porque se abandonó
        jobrunr.verifyNoInteractions();
    }

    @Test
    void comprobante_no_encontrado_no_lanza() {
        UUID id = UUID.randomUUID();
        when(comprobanteRepo.findById(id)).thenReturn(Optional.empty());

        ops.procesarEnvio(id, "PRUEBAS");

        verifyNoInteractions(sriClient, eventoRepo);
    }

    @Test
    void comprobante_en_estado_terminal_skip() {
        Comprobante c = comprobanteAutorizado();  // YA fue autorizado, no debe re-enviarse
        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));

        ops.procesarEnvio(c.getId(), "PRUEBAS");

        verifyNoInteractions(sriClient, eventoRepo);
        verify(comprobanteRepo, never()).save(any());
    }

    @Test
    void comprobante_DEVUELTA_se_puede_reenviar_tras_corregir() {
        // Caso real: cliente corrige y reenvía. El job procesa la DEVUELTA igual que FIRMADA.
        Comprobante c = comprobanteFirmado();
        c.marcarDevuelto("43", "FIRMA INVALIDA");
        // Simulamos corrección: nuevo XML firmado + reset al estado FIRMADA via marcarFirmado
        c.marcarFirmado(c.getXmlFirmado());  // de DEVUELTA → FIRMADA
        assertThat(c.getEstado()).isEqualTo(Estado.FIRMADA);

        when(comprobanteRepo.findById(c.getId())).thenReturn(Optional.of(c));
        when(sriClient.recepcion(any(), eq("PRUEBAS"), eq(CLAVE_ACCESO)))
            .thenReturn(new RespuestaRecepcion(RespuestaRecepcion.Estado.RECIBIDA, CLAVE_ACCESO, List.of()));

        ops.procesarEnvio(c.getId(), "PRUEBAS");

        assertThat(c.getEstado()).isEqualTo(Estado.ENVIADA);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────

    private Comprobante comprobanteFirmado() {
        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRUEBAS", 1L, "001-001-000000001",
            CLAVE_ACCESO, LocalDate.now());
        c.receptor("04", "1790012345001", "Cliente", null, null, null);
        c.formaPago("01", 0);
        c.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));
        c.marcarFirmado(new byte[]{1, 2, 3});  // bytes no importan para tests, solo no-null
        return c;
    }

    private Comprobante comprobanteAutorizado() {
        Comprobante c = comprobanteFirmado();
        c.marcarEnviado();
        c.marcarAutorizado(new byte[]{4, 5, 6}, CLAVE_ACCESO, java.time.Instant.now());
        return c;
    }
}
