package ec.tarius.forseti.emision.jobs;

import ec.tarius.forseti.emision.ComprobanteDetalleRepository;
import ec.tarius.forseti.emision.ComprobanteEventoRepository;
import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import ec.tarius.forseti.emision.domain.ComprobanteEvento;
import ec.tarius.forseti.emision.pdf.RideRenderer;
import ec.tarius.forseti.emision.sri.CategoriaErrorSri;
import ec.tarius.forseti.emision.sri.MensajeSri;
import ec.tarius.forseti.emision.sri.RespuestaAutorizacion;
import ec.tarius.forseti.emision.sri.SriSoapClient;
import ec.tarius.forseti.empresa.EmpresaRepository;
import ec.tarius.forseti.empresa.domain.Empresa;
import ec.tarius.forseti.shared.email.EmailService;
import org.jobrunr.scheduling.BackgroundJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lógica @Transactional del job de consulta de autorización. Mismo patrón que EnvioJobOperations.
 */
@Component
public class AutorizacionJobOperations {

    private static final Logger log = LoggerFactory.getLogger(AutorizacionJobOperations.class);

    private final ComprobanteRepository comprobanteRepo;
    private final ComprobanteDetalleRepository detalleRepo;
    private final ComprobanteEventoRepository eventoRepo;
    private final EmpresaRepository empresaRepo;
    private final SriSoapClient sriClient;
    private final RideRenderer rideRenderer;
    private final EmailService emailService;

    public AutorizacionJobOperations(ComprobanteRepository comprobanteRepo,
                                      ComprobanteDetalleRepository detalleRepo,
                                      ComprobanteEventoRepository eventoRepo,
                                      EmpresaRepository empresaRepo,
                                      SriSoapClient sriClient,
                                      RideRenderer rideRenderer,
                                      EmailService emailService) {
        this.comprobanteRepo = comprobanteRepo;
        this.detalleRepo = detalleRepo;
        this.eventoRepo = eventoRepo;
        this.empresaRepo = empresaRepo;
        this.sriClient = sriClient;
        this.rideRenderer = rideRenderer;
        this.emailService = emailService;
    }

    @Transactional
    public void consultar(UUID comprobanteId, String ambiente) {
        Comprobante c = comprobanteRepo.findById(comprobanteId).orElse(null);
        if (c == null) {
            log.warn("AutorizacionJob: comprobante {} no encontrado", comprobanteId);
            return;
        }
        if (c.getEstado() != Estado.ENVIADA && c.getEstado() != Estado.EN_PROCESO) {
            log.info("AutorizacionJob: comprobante {} en estado {}, skip", comprobanteId, c.getEstado());
            return;
        }

        try {
            RespuestaAutorizacion r = sriClient.autorizacion(c.getClaveAcceso(), ambiente);
            Estado anterior = c.getEstado();

            switch (r.estado()) {
                case AUTORIZADO -> {
                    c.marcarAutorizado(r.xmlAutorizado(), r.numeroAutorizacion(), r.fechaAutorizacion());
                    comprobanteRepo.save(c);
                    eventoRepo.save(ComprobanteEvento.nuevo(
                        c.getEmpresaId(), c.getId(), anterior, Estado.AUTORIZADA,
                        "Autorizado por SRI — numero_aut=" + r.numeroAutorizacion()));
                    log.info("Comprobante {} AUTORIZADO", c.getClaveAcceso());
                    // Sprint 4: enviar correo al receptor con XML + RIDE (si tiene email y
                    // no se envió antes — idempotente).
                    enviarCorreoAlReceptor(c);
                }
                case NO_AUTORIZADO -> {
                    String msg = r.mensajes().stream().map(MensajeSri::resumen)
                        .collect(Collectors.joining(" | "));
                    String codigo = r.mensajes().isEmpty() ? null : r.mensajes().get(0).identificador();
                    CategoriaErrorSri categoria = CategoriaErrorSri.de(codigo);
                    c.marcarNoAutorizado(codigo, msg);
                    comprobanteRepo.save(c);
                    eventoRepo.save(ComprobanteEvento.nuevo(
                        c.getEmpresaId(), c.getId(), anterior, Estado.NO_AUTORIZADA,
                        "SRI NO_AUTORIZADO [" + categoria + "] — " + msg));

                    if (categoria.requiereAlerta()) {
                        // BUG_FIRMADOR (error 39) post-Sprint-3-Fase-E es ALARMA SERIA:
                        // significa que alguien rompió el firmador. Loggear ERROR para que el
                        // dashboard de prod lo agarre. (Una alerta real por mail vendrá en
                        // Fase E cuando montemos el dashboard.)
                        log.error("ALERTA — Comprobante {} rechazado por SRI con código {} ({}). "
                            + "El fix del firmador (Sprint 3 Fase E) puede haber regresionado. "
                            + "Mensaje: {}", c.getClaveAcceso(), codigo, categoria, msg);
                    } else {
                        log.warn("Comprobante {} NO_AUTORIZADO [{}]: {}",
                            c.getClaveAcceso(), categoria, msg);
                    }
                }
                case EN_PROCESAMIENTO, NO_ENCONTRADO -> {
                    if (anterior == Estado.ENVIADA) {
                        c.marcarEnProceso();
                    }
                    programarSiguienteConsulta(c, ambiente,
                        r.estado() == RespuestaAutorizacion.Estado.NO_ENCONTRADO
                            ? "SRI aún no procesa la clave"
                            : "SRI sigue procesando");
                }
            }
        } catch (SriSoapClient.SriIOException e) {
            programarSiguienteConsulta(c, ambiente, "IO: " + e.getMessage());
        } catch (SriSoapClient.SriProtocolException e) {
            abandonar(c, "Protocol: " + e.getMessage());
        }
    }

    /**
     * Envía correo al receptor con XML autorizado + RIDE PDF adjuntos. Solo se ejecuta si:
     *  - el receptor tiene email,
     *  - no se envió antes (idempotente vía {@code correo_enviado_at}).
     *
     * Si falla la generación del RIDE o el SMTP, NO rollback: el comprobante ya quedó
     * AUTORIZADA en la TX. El log queda con WARN para que el operador pueda reintentar
     * manualmente desde la UI más tarde (feature de re-envío queda para iteración).
     */
    private void enviarCorreoAlReceptor(Comprobante c) {
        if (c.getReceptorEmail() == null || c.getReceptorEmail().isBlank()) {
            log.debug("Comprobante {} sin email de receptor — no se envía correo", c.getClaveAcceso());
            return;
        }
        if (c.getCorreoEnviadoAt() != null) {
            log.debug("Comprobante {} ya tiene correo enviado ({}) — skip",
                c.getClaveAcceso(), c.getCorreoEnviadoAt());
            return;
        }
        try {
            Empresa empresa = empresaRepo.findById(c.getEmpresaId()).orElse(null);
            if (empresa == null) {
                log.warn("Comprobante {} sin empresa — no se envía correo", c.getClaveAcceso());
                return;
            }
            var detalles = detalleRepo.findByComprobanteIdOrderByOrdenAsc(c.getId());
            byte[] pdf = rideRenderer.renderizar(c, empresa, detalles);

            emailService.enviarComprobanteAutorizado(
                c.getReceptorEmail(),
                empresa.getRazonSocial(),
                c.getNumeroComprobante(),
                c.getClaveAcceso(),
                c.getNumeroAutorizacion(),
                c.getXmlAutorizado(),
                pdf);

            c.marcarCorreoEnviado();
            comprobanteRepo.save(c);
        } catch (Exception e) {
            // No-throw: el comprobante ya quedó AUTORIZADA y eso es lo importante.
            log.warn("Comprobante {} autorizado pero falló envío de correo a {}: {}",
                c.getClaveAcceso(), c.getReceptorEmail(), e.getMessage());
        }
    }

    private void programarSiguienteConsulta(Comprobante c, String ambiente, String motivo) {
        int intentosPrev = c.getIntentosEnvio();
        if (Backoff.debeAbandonar(intentosPrev + 1)) {
            abandonar(c, "Max polling alcanzado: " + motivo);
            return;
        }
        Instant proxima = Instant.now().plus(Backoff.siguiente(intentosPrev + 1));
        c.registrarIntentoFallido(proxima);
        eventoRepo.save(ComprobanteEvento.nuevo(
            c.getEmpresaId(), c.getId(), c.getEstado(), c.getEstado(),
            "Consulta " + (intentosPrev + 1) + "/" + Backoff.MAX_INTENTOS + ": " + motivo)
            .conDato("siguiente_intento_at", proxima.toString()));
        comprobanteRepo.save(c);
        BackgroundJobRequest.schedule(proxima,
            new ConsultarAutorizacionRequest(c.getId(), c.getEmpresaId(), ambiente));
    }

    private void abandonar(Comprobante c, String motivo) {
        Estado anterior = c.getEstado();
        try {
            c.cambiarEstado(Estado.ABANDONADA);
        } catch (IllegalStateException ignore) {}
        eventoRepo.save(ComprobanteEvento.nuevo(
            c.getEmpresaId(), c.getId(), anterior, Estado.ABANDONADA, motivo));
        comprobanteRepo.save(c);
        log.warn("Comprobante {} abandonado en autorización: {}", c.getClaveAcceso(), motivo);
    }
}
