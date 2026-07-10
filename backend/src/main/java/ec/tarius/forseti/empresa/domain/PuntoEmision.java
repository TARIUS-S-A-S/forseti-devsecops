package ec.tarius.forseti.empresa.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "punto_emision")
public class PuntoEmision {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    /** Código SRI de 3 dígitos: 001, 002… */
    @Column(name = "codigo", nullable = false, length = 3)
    private String codigo;

    @Column(name = "descripcion", length = 200)
    private String descripcion;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    @Column(name = "actualizado_at", nullable = false)
    private Instant actualizadoAt = Instant.now();

    protected PuntoEmision() {}

    public static PuntoEmision nuevo(UUID empresaId, UUID establecimientoId, String codigo, String descripcion) {
        PuntoEmision p = new PuntoEmision();
        p.empresaId = empresaId;
        p.establecimientoId = establecimientoId;
        p.codigo = codigo;
        p.descripcion = descripcion;
        return p;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public UUID getEstablecimientoId() { return establecimientoId; }
    public String getCodigo() { return codigo; }
    public String getDescripcion() { return descripcion; }
    public boolean isActivo() { return activo; }
    public Instant getCreadoAt() { return creadoAt; }

    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public void setActivo(boolean activo) { this.activo = activo; }
}
