package ec.tarius.forseti.empresa.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "establecimiento")
public class Establecimiento {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    /** Código SRI de 3 dígitos: 001, 002… */
    @Column(name = "codigo", nullable = false, length = 3)
    private String codigo;

    @Column(name = "nombre", length = 200)
    private String nombre;

    @Column(name = "direccion", columnDefinition = "text")
    private String direccion;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    @Column(name = "actualizado_at", nullable = false)
    private Instant actualizadoAt = Instant.now();

    protected Establecimiento() {}

    public static Establecimiento nuevo(UUID empresaId, String codigo, String nombre, String direccion) {
        Establecimiento e = new Establecimiento();
        e.empresaId = empresaId;
        e.codigo = codigo;
        e.nombre = nombre;
        e.direccion = direccion;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public String getDireccion() { return direccion; }
    public boolean isActivo() { return activo; }
    public Instant getCreadoAt() { return creadoAt; }

    public void setNombre(String nombre) { this.nombre = nombre; }
    public void setDireccion(String direccion) { this.direccion = direccion; }
    public void setActivo(boolean activo) { this.activo = activo; }
}
