package ec.tarius.forseti.obligacion.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Catálogo COMPLETO de obligaciones tributarias / regulatorias de Ecuador.
 * Es shared (no tenant-aware): mismos datos para todos. Cada empresa activa las suyas.
 * Actualizar = INSERT/UPDATE, no requiere redeploy (RNF-8).
 */
@Entity
@Table(name = "obligacion_catalogo")
public class ObligacionCatalogo {

    public enum Categoria {
        SRI_DECLARACION, SRI_ANEXO, SUPERCIA, MUNICIPIO, IESS_MDT, INTERNA
    }

    public enum Periodicidad {
        MENSUAL, SEMESTRAL, ANUAL, UNICA, EVENTUAL
    }

    @Id
    @Column(name = "codigo", length = 40)
    private String codigo;

    @Column(name = "nombre", nullable = false, length = 200)
    private String nombre;

    @Column(name = "descripcion", columnDefinition = "text")
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "categoria", nullable = false, length = 30)
    private Categoria categoria;

    @Enumerated(EnumType.STRING)
    @Column(name = "periodicidad", nullable = false, length = 20)
    private Periodicidad periodicidad;

    @Column(name = "regla_fecha", columnDefinition = "text")
    private String reglaFecha;

    /** Condiciones declarativas (JSON) — los generadores las evalúan contra el perfil de la empresa. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "aplica_si", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> aplicaSi = new HashMap<>();

    @Column(name = "bloqueante", nullable = false)
    private boolean bloqueante = true;

    @Column(name = "alerta_dias", columnDefinition = "int[]")
    private Integer[] alertaDias;

    @Column(name = "orden", nullable = false)
    private int orden = 100;

    @Column(name = "activa", nullable = false)
    private boolean activa = true;

    @Column(name = "creada_at", nullable = false, updatable = false)
    private Instant creadaAt = Instant.now();

    @Column(name = "actualizada_at", nullable = false)
    private Instant actualizadaAt = Instant.now();

    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public String getDescripcion() { return descripcion; }
    public Categoria getCategoria() { return categoria; }
    public Periodicidad getPeriodicidad() { return periodicidad; }
    public String getReglaFecha() { return reglaFecha; }
    public Map<String, Object> getAplicaSi() { return aplicaSi; }
    public boolean isBloqueante() { return bloqueante; }
    public Integer[] getAlertaDias() { return alertaDias; }
    public int getOrden() { return orden; }
    public boolean isActiva() { return activa; }
    public Instant getCreadaAt() { return creadaAt; }
    public Instant getActualizadaAt() { return actualizadaAt; }
}
