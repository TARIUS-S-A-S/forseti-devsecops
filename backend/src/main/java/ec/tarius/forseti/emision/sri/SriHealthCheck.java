package ec.tarius.forseti.emision.sri;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Resultado de un ping al WS del SRI para tracking de disponibilidad.
 *
 * Estados:
 *   - ARRIBA:    SRI respondió OK y latencia razonable.
 *   - DEGRADADO: respondió pero lento (>5s) o con respuesta parcial.
 *   - CAIDO:     timeout, conexión rechazada, o 5xx.
 *
 * NO es tenant-aware (sin RLS) — es estado global del WS SRI.
 */
@Entity
@Table(name = "sri_health_check")
public class SriHealthCheck {

    public enum Estado { ARRIBA, DEGRADADO, CAIDO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String ambiente;  // PRUEBAS | PRODUCCION

    @Column(nullable = false)
    private String estado;    // ARRIBA | DEGRADADO | CAIDO

    @Column(name = "latencia_ms")
    private Integer latenciaMs;

    @Column
    private String mensaje;

    @Column(name = "ts_check", nullable = false)
    private Instant tsCheck;

    protected SriHealthCheck() {}

    public static SriHealthCheck arriba(String ambiente, int latenciaMs) {
        SriHealthCheck h = new SriHealthCheck();
        h.ambiente = ambiente;
        h.estado = Estado.ARRIBA.name();
        h.latenciaMs = latenciaMs;
        h.tsCheck = Instant.now();
        return h;
    }

    public static SriHealthCheck degradado(String ambiente, int latenciaMs, String mensaje) {
        SriHealthCheck h = new SriHealthCheck();
        h.ambiente = ambiente;
        h.estado = Estado.DEGRADADO.name();
        h.latenciaMs = latenciaMs;
        h.mensaje = mensaje;
        h.tsCheck = Instant.now();
        return h;
    }

    public static SriHealthCheck caido(String ambiente, String mensaje) {
        SriHealthCheck h = new SriHealthCheck();
        h.ambiente = ambiente;
        h.estado = Estado.CAIDO.name();
        h.mensaje = mensaje;
        h.tsCheck = Instant.now();
        return h;
    }

    public Long getId() { return id; }
    public String getAmbiente() { return ambiente; }
    public String getEstado() { return estado; }
    public Estado getEstadoEnum() { return Estado.valueOf(estado); }
    public Integer getLatenciaMs() { return latenciaMs; }
    public String getMensaje() { return mensaje; }
    public Instant getTsCheck() { return tsCheck; }
}
