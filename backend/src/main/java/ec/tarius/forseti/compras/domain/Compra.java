package ec.tarius.forseti.compras.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una compra/gasto recibido por la empresa. HU-F2.
 *
 * Reglas:
 *   - {@code origen=XML}: vino del parser de XML SRI recibido — campos cargan solos.
 *   - {@code origen=MANUAL}: el usuario digita todo.
 *   - {@code anulada=true} (HU-F6): la compra queda visible para auditoría pero
 *     NO entra en totales ni declaraciones (RNF-6). NUNCA se hace DELETE de
 *     comprobantes financieros.
 *   - {@code deducible}: si va al gasto deducible de la declaración 104/RIMPE/general.
 */
@Entity
@Table(name = "compra")
public class Compra {

    public enum EstadoPago { PENDIENTE, PAGADO, PARCIAL }
    public enum Origen { MANUAL, XML }
    public enum TipoDocumento { FACTURA, NOTA_CREDITO, NOTA_DEBITO, LIQUIDACION_COMPRA, RECIBO, OTRO }

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "fecha_emision", nullable = false)
    private LocalDate fechaEmision;

    @Column(name = "proveedor_tipo_id", nullable = false, length = 2)
    private String proveedorTipoId = "04";

    @Column(name = "proveedor_identificacion", nullable = false, length = 20)
    private String proveedorIdentificacion;

    @Column(name = "proveedor_razon_social", nullable = false, length = 300)
    private String proveedorRazonSocial;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento", nullable = false, length = 30)
    private TipoDocumento tipoDocumento = TipoDocumento.FACTURA;

    @Column(name = "numero_documento", nullable = false, length = 50)
    private String numeroDocumento;

    @Column(name = "clave_acceso", length = 49)
    private String claveAcceso;

    @Column(name = "fecha_autorizacion_sri")
    private Instant fechaAutorizacionSri;

    @Column(name = "concepto", nullable = false, length = 500)
    private String concepto;

    @Column(name = "categoria_id")
    private UUID categoriaId;

    @Column(name = "base_iva_15", nullable = false)
    private BigDecimal baseIva15 = BigDecimal.ZERO;

    @Column(name = "base_iva_0", nullable = false)
    private BigDecimal baseIva0 = BigDecimal.ZERO;

    @Column(name = "base_no_objeto", nullable = false)
    private BigDecimal baseNoObjeto = BigDecimal.ZERO;

    @Column(name = "base_exento", nullable = false)
    private BigDecimal baseExento = BigDecimal.ZERO;

    @Column(name = "valor_iva_15", nullable = false)
    private BigDecimal valorIva15 = BigDecimal.ZERO;

    @Column(name = "retencion_ir", nullable = false)
    private BigDecimal retencionIr = BigDecimal.ZERO;

    @Column(name = "retencion_iva", nullable = false)
    private BigDecimal retencionIva = BigDecimal.ZERO;

    @Column(name = "total", nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "deducible", nullable = false)
    private boolean deducible = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_pago", nullable = false, length = 15)
    private EstadoPago estadoPago = EstadoPago.PENDIENTE;

    @Column(name = "fecha_pago")
    private LocalDate fechaPago;

    @Column(name = "forma_pago", length = 2)
    private String formaPago;

    @Column(name = "xml_recibido", columnDefinition = "bytea")
    private byte[] xmlRecibido;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", nullable = false, length = 10)
    private Origen origen = Origen.MANUAL;

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

    protected Compra() {}

    public static Compra nueva(UUID empresaId, LocalDate fecha,
                                String proveedorTipoId, String proveedorIdent, String proveedorRazon,
                                String numeroDocumento, String concepto, BigDecimal total,
                                Origen origen) {
        Compra c = new Compra();
        c.empresaId = empresaId;
        c.fechaEmision = fecha;
        c.proveedorTipoId = proveedorTipoId;
        c.proveedorIdentificacion = proveedorIdent;
        c.proveedorRazonSocial = proveedorRazon;
        c.numeroDocumento = numeroDocumento;
        c.concepto = concepto;
        c.total = total;
        c.origen = origen;
        return c;
    }

    /**
     * Anula la compra (HU-F6 / RNF-6). NO se borra: queda con anulada=true para
     * auditoría pero ya NO entra en totales ni declaraciones.
     */
    public void anular(UUID porUsuarioId, String motivo) {
        if (anulada) return;
        this.anulada = true;
        this.anuladaAt = Instant.now();
        this.anuladaPorUsuarioId = porUsuarioId;
        this.motivoAnulacion = motivo;
    }

    public void marcarPagado(LocalDate fechaPago, String formaPago) {
        this.estadoPago = EstadoPago.PAGADO;
        this.fechaPago = fechaPago;
        this.formaPago = formaPago;
    }

    public void asignarCategoria(UUID categoriaId) { this.categoriaId = categoriaId; }

    public void cargarXmlSri(byte[] xml, String claveAcceso, Instant fechaAutSri) {
        this.xmlRecibido = xml;
        this.claveAcceso = claveAcceso;
        this.fechaAutorizacionSri = fechaAutSri;
        this.origen = Origen.XML;
    }

    public void bases(BigDecimal baseIva15, BigDecimal baseIva0, BigDecimal baseNoObjeto,
                       BigDecimal baseExento, BigDecimal valorIva15) {
        this.baseIva15 = baseIva15;
        this.baseIva0 = baseIva0;
        this.baseNoObjeto = baseNoObjeto;
        this.baseExento = baseExento;
        this.valorIva15 = valorIva15;
    }

    public void retenciones(BigDecimal ir, BigDecimal iva) {
        this.retencionIr = ir;
        this.retencionIva = iva;
    }

    // Getters
    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public LocalDate getFechaEmision() { return fechaEmision; }
    public String getProveedorTipoId() { return proveedorTipoId; }
    public String getProveedorIdentificacion() { return proveedorIdentificacion; }
    public String getProveedorRazonSocial() { return proveedorRazonSocial; }
    public TipoDocumento getTipoDocumento() { return tipoDocumento; }
    public String getNumeroDocumento() { return numeroDocumento; }
    public String getClaveAcceso() { return claveAcceso; }
    public Instant getFechaAutorizacionSri() { return fechaAutorizacionSri; }
    public String getConcepto() { return concepto; }
    public UUID getCategoriaId() { return categoriaId; }
    public BigDecimal getBaseIva15() { return baseIva15; }
    public BigDecimal getBaseIva0() { return baseIva0; }
    public BigDecimal getBaseNoObjeto() { return baseNoObjeto; }
    public BigDecimal getBaseExento() { return baseExento; }
    public BigDecimal getValorIva15() { return valorIva15; }
    public BigDecimal getRetencionIr() { return retencionIr; }
    public BigDecimal getRetencionIva() { return retencionIva; }
    public BigDecimal getTotal() { return total; }
    public boolean isDeducible() { return deducible; }
    public EstadoPago getEstadoPago() { return estadoPago; }
    public LocalDate getFechaPago() { return fechaPago; }
    public String getFormaPago() { return formaPago; }
    public byte[] getXmlRecibido() { return xmlRecibido; }
    public Origen getOrigen() { return origen; }
    public boolean isAnulada() { return anulada; }
    public Instant getAnuladaAt() { return anuladaAt; }
    public UUID getAnuladaPorUsuarioId() { return anuladaPorUsuarioId; }
    public String getMotivoAnulacion() { return motivoAnulacion; }
    public UUID getCreadoPorUsuarioId() { return creadoPorUsuarioId; }
    public Instant getCreadoAt() { return creadoAt; }
    public Instant getActualizadoAt() { return actualizadoAt; }

    public void setTipoDocumento(TipoDocumento t) { this.tipoDocumento = t; }
    public void setConcepto(String c) { this.concepto = c; }
    public void setDeducible(boolean d) { this.deducible = d; }
    public void setCreadoPorUsuarioId(UUID u) { this.creadoPorUsuarioId = u; }
}
