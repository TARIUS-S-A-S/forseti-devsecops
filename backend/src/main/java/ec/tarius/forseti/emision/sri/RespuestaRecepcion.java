package ec.tarius.forseti.emision.sri;

import java.util.List;

/**
 * Respuesta del WS recepcionComprobantesOffline del SRI.
 * Estados posibles: RECIBIDA (el comprobante pasa a "en proceso" en el SRI), DEVUELTA (rechazado).
 */
public record RespuestaRecepcion(
    Estado estado,
    String claveAcceso,
    List<MensajeSri> mensajes
) {
    public enum Estado { RECIBIDA, DEVUELTA }

    public boolean fueRecibida() { return estado == Estado.RECIBIDA; }
    public boolean fueDevuelta() { return estado == Estado.DEVUELTA; }
}
