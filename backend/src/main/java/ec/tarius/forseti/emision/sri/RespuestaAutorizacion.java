package ec.tarius.forseti.emision.sri;

import java.time.Instant;
import java.util.List;

/**
 * Respuesta del WS autorizacionComprobantesOffline del SRI.
 *
 * Estados posibles:
 *   - AUTORIZADO: comprobante válido, viene con numeroAutorizacion (== claveAcceso post-2024),
 *     fechaAutorizacion y el XML autorizado completo (que es lo que conservás 7 años).
 *   - NO_AUTORIZADO: rechazo definitivo; viene con mensajes describiendo el error.
 *   - EN_PROCESAMIENTO: el SRI todavía no terminó; hay que volver a consultar más tarde.
 *
 * Si el SRI responde sin autorizaciones (numeroComprobantes=0), {@link #estado} es NO_ENCONTRADO.
 */
public record RespuestaAutorizacion(
    Estado estado,
    String claveAccesoConsultada,
    String numeroAutorizacion,
    Instant fechaAutorizacion,
    String ambiente,
    byte[] xmlAutorizado,
    List<MensajeSri> mensajes
) {
    public enum Estado { AUTORIZADO, NO_AUTORIZADO, EN_PROCESAMIENTO, NO_ENCONTRADO }

    public boolean fueAutorizado() { return estado == Estado.AUTORIZADO; }
    public boolean estaEnProceso() { return estado == Estado.EN_PROCESAMIENTO; }
    public boolean fueRechazado() { return estado == Estado.NO_AUTORIZADO; }
}
