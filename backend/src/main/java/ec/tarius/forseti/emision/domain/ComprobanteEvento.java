package ec.tarius.forseti.emision.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bitácora de transiciones de estado del comprobante.
 * Se escribe una fila por cada cambio: BORRADOR→FIRMADA, FIRMADA→ENVIADA, etc.
 * Es la fuente de verdad para auditar "¿qué pasó con esta factura?".
 */
@Entity
@Table(name = "comprobante_evento")
public class ComprobanteEvento {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "comprobante_id", nullable = false)
    private UUID comprobanteId;

    @Column(name = "estado_anterior", length = 20)
    private String estadoAnterior;

    @Column(name = "estado_nuevo", nullable = false, length = 20)
    private String estadoNuevo;

    @Column(name = "mensaje", columnDefinition = "text")
    private String mensaje;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> datos = new LinkedHashMap<>();

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    protected ComprobanteEvento() {}

    public static ComprobanteEvento nuevo(UUID empresaId, UUID comprobanteId,
                                           Comprobante.Estado estadoAnterior,
                                           Comprobante.Estado estadoNuevo,
                                           String mensaje) {
        ComprobanteEvento e = new ComprobanteEvento();
        e.empresaId = empresaId;
        e.comprobanteId = comprobanteId;
        e.estadoAnterior = estadoAnterior != null ? estadoAnterior.name() : null;
        e.estadoNuevo = estadoNuevo.name();
        e.mensaje = mensaje;
        return e;
    }

    public ComprobanteEvento conDato(String clave, Object valor) {
        this.datos.put(clave, valor);
        return this;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public UUID getComprobanteId() { return comprobanteId; }
    public String getEstadoAnterior() { return estadoAnterior; }
    public String getEstadoNuevo() { return estadoNuevo; }
    public String getMensaje() { return mensaje; }
    public Map<String, Object> getDatos() { return datos; }
    public Instant getCreadoAt() { return creadoAt; }
}
