package ec.tarius.forseti.emision.sri;

/**
 * Mensaje devuelto por el SRI (recepción o autorización).
 * El SRI usa "identificador" como código numérico y "tipo" como severidad.
 * "informacionAdicional" suele tener el detalle técnico (qué línea del XML, qué dato).
 */
public record MensajeSri(
    String identificador,
    String mensaje,
    String informacionAdicional,
    String tipo  // ERROR | INFORMATIVO | ADVERTENCIA
) {
    public boolean esError() {
        return "ERROR".equalsIgnoreCase(tipo);
    }

    /** Mensaje en una sola línea, listo para mostrar al usuario. */
    public String resumen() {
        StringBuilder sb = new StringBuilder();
        if (identificador != null) sb.append("[").append(identificador).append("] ");
        if (mensaje != null) sb.append(mensaje);
        if (informacionAdicional != null && !informacionAdicional.isBlank()) {
            sb.append(" — ").append(informacionAdicional);
        }
        return sb.toString();
    }
}
