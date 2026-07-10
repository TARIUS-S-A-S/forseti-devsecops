package ec.tarius.forseti.compras.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ingreso registrado manualmente (HU-F1). Para ventas previas a Forseti o que
 * no se emiten via Forseti (ej. el cliente las emitió). El flujo normal de
 * ventas es HU-F7 (emisión SRI vía Comprobante).
 *
 * Anulación HU-F6: igual que Compra — soft-delete con flag + motivo.
 */
@Entity
@Table(name = "ingreso_manual")
public class IngresoManual {

    public enum EstadoCobro { PENDIENTE, COBRADO, PARCIAL }

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "fecha_emision", nullable = false)
    private LocalDate fechaEmision;

    @Column(name = "cliente_identificacion", length = 20)
    private String clienteIdentificacion;

    @Column(name = "cliente_razon_social", nullable = false, length = 300)
    private String clienteRazonSocial;

    @Column(name = "concepto", nullable = false, length = 500)
    private String concepto;

    @Column(name = "base_iva_15", nullable = false)
    private BigDecimal baseIva15 = BigDecimal.ZERO;

    @Column(name = "base_iva_0", nullable = false)
    private BigDecimal baseIva0 = BigDecimal.ZERO;

    @Column(name = "valor_iva_15", nullable = false)
    private BigDecimal valorIva15 = BigDecimal.ZERO;

    @Column(name = "retencion_recibida", nullable = false)
    private BigDecimal retencionRecibida = BigDecimal.ZERO;

    @Column(name = "total", nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_cobro", nullable = false, length = 15)
    private EstadoCobro estadoCobro = EstadoCobro.PENDIENTE;

    @Column(name = "fecha_cobro")
    private LocalDate fechaCobro;

    @Column(name = "anulada", nullable = false)
    private boolean anulada = false;

    @Column(name = "anulada_at")
    private Instant anuladaAt;

    @Column(name = "anulada_por_usuario_id")
    private UUID anuladaPorUsuarioId;

    @Column(name = "motivo_anulacion", columnDefinition = "text")
    private String motivoAnulacion;

    @Column(name = "creado_por_usuario_id")
    private UUID creadoPorUsuarioId;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    @Column(name = "actualizado_at", nullable = false)
    private Instant actualizadoAt = Instant.now();

    protected IngresoManual() {}

    public static IngresoManual nuevo(UUID empresaId, LocalDate fecha,
                                       String clienteRazon, String concepto,
                                       BigDecimal total) {
        IngresoManual i = new IngresoManual();
        i.empresaId = empresaId;
        i.fechaEmision = fecha;
        i.clienteRazonSocial = clienteRazon;
        i.concepto = concepto;
        i.total = total;
        return i;
    }

    public void anular(UUID porUsuarioId, String motivo) {
        if (anulada) return;
        this.anulada = true;
        this.anuladaAt = Instant.now();
        this.anuladaPorUsuarioId = porUsuarioId;
        this.motivoAnulacion = motivo;
    }

    public void marcarCobrado(LocalDate fecha) {
        this.estadoCobro = EstadoCobro.COBRADO;
        this.fechaCobro = fecha;
    }

    public void bases(BigDecimal baseIva15, BigDecimal baseIva0, BigDecimal valorIva15,
                       BigDecimal retencionRecibida) {
        this.baseIva15 = baseIva15;
        this.baseIva0 = baseIva0;
        this.valorIva15 = valorIva15;
        this.retencionRecibida = retencionRecibida;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public LocalDate getFechaEmision() { return fechaEmision; }
    public String getClienteIdentificacion() { return clienteIdentificacion; }
    public String getClienteRazonSocial() { return clienteRazonSocial; }
    public String getConcepto() { return concepto; }
    public BigDecimal getBaseIva15() { return baseIva15; }
    public BigDecimal getBaseIva0() { return baseIva0; }
    public BigDecimal getValorIva15() { return valorIva15; }
    public BigDecimal getRetencionRecibida() { return retencionRecibida; }
    public BigDecimal getTotal() { return total; }
    public EstadoCobro getEstadoCobro() { return estadoCobro; }
    public LocalDate getFechaCobro() { return fechaCobro; }
    public boolean isAnulada() { return anulada; }
    public Instant getAnuladaAt() { return anuladaAt; }
    public UUID getAnuladaPorUsuarioId() { return anuladaPorUsuarioId; }
    public String getMotivoAnulacion() { return motivoAnulacion; }
    public UUID getCreadoPorUsuarioId() { return creadoPorUsuarioId; }
    public Instant getCreadoAt() { return creadoAt; }
    public Instant getActualizadoAt() { return actualizadoAt; }

    public void setClienteIdentificacion(String s) { this.clienteIdentificacion = s; }
    public void setCreadoPorUsuarioId(UUID u) { this.creadoPorUsuarioId = u; }
}
