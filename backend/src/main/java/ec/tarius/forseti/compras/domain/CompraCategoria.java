package ec.tarius.forseti.compras.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Catálogo SHARED de categorías de compra (servicios, software, equipos, …).
 * No es tenant-aware: las categorías son datos transversales a todas las empresas.
 * Si en el futuro una empresa quiere su categoría propia, se agrega tabla
 * `compra_categoria_empresa` (tenant-aware) — por ahora alcanza con shared.
 */
@Entity
@Table(name = "compra_categoria")
public class CompraCategoria {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "codigo", nullable = false, unique = true, length = 40)
    private String codigo;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "descripcion", columnDefinition = "text")
    private String descripcion;

    @Column(name = "orden", nullable = false)
    private int orden;

    @Column(name = "activa", nullable = false)
    private boolean activa = true;

    @Column(name = "creada_at", nullable = false, updatable = false)
    private Instant creadaAt = Instant.now();

    protected CompraCategoria() {}

    public UUID getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNombre() { return nombre; }
    public String getDescripcion() { return descripcion; }
    public int getOrden() { return orden; }
    public boolean isActiva() { return activa; }
    public Instant getCreadaAt() { return creadaAt; }
}
