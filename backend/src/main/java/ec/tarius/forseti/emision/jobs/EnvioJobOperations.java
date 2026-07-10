package ec.tarius.forseti.emision.jobs;

import ec.tarius.forseti.emision.ComprobanteEventoRepository;
import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import ec.tarius.forseti.emision.domain.ComprobanteEvento;
import ec.tarius.forseti.emision.sri.CategoriaErrorSri;
import ec.tarius.forseti.emision.sri.MensajeSri;
import ec.tarius.forseti.emision.sri.RespuestaRecepcion;
import ec.tarius.forseti.emision.sri.SriSoapClient;
import org.jobrunr.scheduling.BackgroundJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lógica @Transactional del job de envío. Separada del entry point (EnvioJob) para que
 * el TenantTransactionAdvice corra dentro de esta TX (Order MAX_VALUE) y setee app.empresa_id.
 */
@Component
public class EnvioJobOperations {

    private static final Logger log = LoggerFactory.getLogger(EnvioJobOperations.class);

    private final ComprobanteRepository comprobanteRepo;
    private final ComprobanteEventoRepository eventoRepo;
    private final SriSoapClient sriClient;

    public EnvioJobOperations(ComprobanteRepository comprobanteRepo,
                               ComprobanteEventoRepository eventoRepo,
                               SriSoapClient sriClient) {
        this.comprobanteRepo = comprobanteRepo;
        this.eventoRepo = eventoRepo;
        this.sriClient = sriClient;
    }

    @Transactional
    public void procesarEnvio(UUID comprobanteId, String ambiente) {
        Comprobante c = comprobanteRepo.findById(comprobanteId).orElse(null);
        if (c == null) {
            log.warn("EnvioJob: comprobante {} no encontrado (¿borrado entre encolado y ejecución?)", comprobanteId);
            return;
        }
        if (c.getEstado() != Estado.FIRMADA && c.getEstado() != Estado.DEVUELTA) {
            log.info("EnvioJob: comprobante {} ya está en estado {}, skip", comprobanteId, c.getEstado());
            return;
        }
        if (c.getXmlFirmado() == null) {
            log.error("EnvioJob: comprobante {} no tiene xml_firmado — abandonando", comprobanteId);
            c.cambiarEstado(Estado.ABANDONADA);
            eventoRepo.save(ComprobanteEvento.nuevo(
                c.getEmpresaId(), c.getId(), c.getEstado(), Estado.ABANDONADA,
                "Sin XML firmado — flow corrupto"));
            comprobanteRepo.save(c);
            return;
        }

        try {
            RespuestaRecepcion r = sriClient.recepcion(
                c.getXmlFirmado(), ambiente, c.getClaveAcceso());

            Estado anterior = c.getEstado();
            if (r.fueRecibida()) {
                c.marcarEnviado();
                comprobanteRepo.save(c);
                eventoRepo.save(ComprobanteEvento.nuevo(
                    c.getEmpresaId(), c.getId(), anterior, Estado.ENVIADA,
                    "Recepción SRI: RECIBIDA"));
                // Encolar consulta de autorización con delay corto (5s) para dar tiempo al SRI
                BackgroundJobRequest.schedule(
                    Instant.now().plusSeconds(5),
                    new ConsultarAutorizacionRequest(comprobanteId, c.getEmpresaId(), ambiente));
            } else {
                String msg = r.mensajes().stream()
                    .map(MensajeSri::resumen)
                    .collect(Collectors.joining(" | "));
                String codigo = r.mensajes().isEmpty() ? null : r.mensajes().get(0).identificador();
                CategoriaErrorSri categoria = CategoriaErrorSri.de(codigo);
                c.marcarDevuelto(codigo, msg);
                comprobanteRepo.save(c);
                eventoRepo.save(ComprobanteEvento.nuevo(
                    c.getEmpresaId(), c.getId(), anterior, Estado.DEVUELTA,
                    "Recepción SRI: DEVUELTA [" + categoria + "] — " + msg));

                if (categoria.requiereAlerta()) {
                    log.error("ALERTA — Comprobante {} devuelto por SRI en recepción con código {} ({}). "
                        + "Posible regresión del firmador. Mensaje: {}",
                        c.getClaveAcceso(), codigo, categoria, msg);
                }
            }
        } catch (SriSoapClient.SriIOException e) {
            programarReintento(c, "Recepción IO: " + e.getMessage(), ambiente, /*envio=*/true);
        } catch (SriSoapClient.SriProtocolException e) {
            abandonar(c, "Recepción protocol: " + e.getMessage());
        }
    }

    private void programarReintento(Comprobante c, String motivo, String ambiente, boolean envio) {
        int intentosPrev = c.getIntentosEnvio();
        if (Backoff.debeAbandonar(intentosPrev + 1)) {
            abandonar(c, "Max reintentos alcanzado: " + motivo);
            return;
        }
        Instant proxima = Instant.now().plus(Backoff.siguiente(intentosPrev + 1));
        c.registrarIntentoFallido(proxima);
        eventoRepo.save(ComprobanteEvento.nuevo(
            c.getEmpresaId(), c.getId(), c.getEstado(), c.getEstado(),
            "Reintento " + (intentosPrev + 1) + "/" + Backoff.MAX_INTENTOS + ": " + motivo)
            .conDato("siguiente_intento_at", proxima.toString()));
        comprobanteRepo.save(c);
        if (envio) {
            BackgroundJobRequest.schedule(proxima,
                new EnviarComprobanteRequest(c.getId(), c.getEmpresaId(), ambiente));
        } else {
            BackgroundJobRequest.schedule(proxima,
                new ConsultarAutorizacionRequest(c.getId(), c.getEmpresaId(), ambiente));
        }
        log.info("Reintento #{} programado para {} en {}", intentosPrev + 1, c.getClaveAcceso(), proxima);
    }

    private void abandonar(Comprobante c, String motivo) {
        Estado anterior = c.getEstado();
        try {
            c.cambiarEstado(Estado.ABANDONADA);
        } catch (IllegalStateException ignore) {
            // si ya estaba en terminal, no podemos transicionar — registrar evento de todas formas
        }
        eventoRepo.save(ComprobanteEvento.nuevo(
            c.getEmpresaId(), c.getId(), anterior, Estado.ABANDONADA, motivo));
        comprobanteRepo.save(c);
        log.warn("Comprobante {} abandonado: {}", c.getClaveAcceso(), motivo);
    }
}
