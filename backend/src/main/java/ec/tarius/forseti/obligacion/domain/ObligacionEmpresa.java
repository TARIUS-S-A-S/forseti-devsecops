package ec.tarius.forseti.obligacion.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Obligaciones activadas por una empresa específica.
 * Cada registro = una obligación del catálogo activada para esta empresa.
 */
@Entity
@Table(name = "obligacion_empresa")
public class ObligacionEmpresa {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "obligacion_codigo", nullable = false, length = 40)
    private String obligacionCodigo;

    @Column(name = "activa", nullable = false)
    private boolean activa = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "activada_por_usuario_id")
    private UUID activadaPorUsuarioId;

    @Column(name = "activada_at", nullable = false, updatable = false)
    private Instant activadaAt = Instant.now();

    @Column(name = "actualizada_at", nullable = false)
    private Instant actualizadaAt = Instant.now();

    protected ObligacionEmpresa() {}

    public static ObligacionEmpresa nueva(UUID empresaId, String obligacionCodigo, UUID activadaPor) {
        ObligacionEmpresa o = new ObligacionEmpresa();
        o.empresaId = empresaId;
        o.obligacionCodigo = obligacionCodigo;
        o.activadaPorUsuarioId = activadaPor;
        return o;
    }

    public void activar() { this.activa = true; }
    public void desactivar() { this.activa = false; }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public String getObligacionCodigo() { return obligacionCodigo; }
    public boolean isActiva() { return activa; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public UUID getActivadaPorUsuarioId() { return activadaPorUsuarioId; }
    public Instant getActivadaAt() { return activadaAt; }
}
