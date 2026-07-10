package ec.tarius.forseti.emision.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Línea de detalle de un comprobante. Una factura tiene 1..N de estas.
 * El cálculo del precio_total_sin_impuesto, base_imponible y valor_impuesto se hace
 * en el service (FacturaCalculator) antes de persistir, para mantener consistencia
 * con lo que va al XML.
 *
 * SRI permite múltiples impuestos por línea (IVA + ICE, p.ej.). Sprint 3 modela
 * un solo impuesto por línea (IVA) — suficiente para HU-F7 parte 1. ICE/IRBPNR
 * se añaden en sprints posteriores cuando aparezca un caso real.
 */
@Entity
@Table(name = "comprobante_detalle")
public class ComprobanteDetalle {

    /** Códigos SRI tabla 17 (porcentaje IVA). */
    public enum CodigoPorcentajeIva {
        IVA_0("0", new BigDecimal("0.00")),
        IVA_15("4", new BigDecimal("15.00")),
        NO_OBJETO("6", new BigDecimal("0.00")),
        EXENTO("7", new BigDecimal("0.00"));

        public final String codigo;
        public final BigDecimal tarifa;
        CodigoPorcentajeIva(String codigo, BigDecimal tarifa) {
            this.codigo = codigo;
            this.tarifa = tarifa;
        }
    }

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "empresa_id", nullable = false)
    private UUID empresaId;

    @Column(name = "comprobante_id", nullable = false)
    private UUID comprobanteId;

    @Column(name = "orden", nullable = false)
    private int orden;

    @Column(name = "codigo_principal", nullable = false, length = 25)
    private String codigoPrincipal;

    @Column(name = "codigo_auxiliar", length = 25)
    private String codigoAuxiliar;

    @Column(name = "descripcion", nullable = false, length = 300)
    private String descripcion;

    @Column(name = "cantidad", nullable = false, precision = 18, scale = 6)
    private BigDecimal cantidad;

    @Column(name = "precio_unitario", nullable = false, precision = 18, scale = 6)
    private BigDecimal precioUnitario;

    @Column(name = "descuento", nullable = false, precision = 14, scale = 2)
    private BigDecimal descuento = BigDecimal.ZERO;

    @Column(name = "precio_total_sin_impuesto", nullable = false, precision = 14, scale = 2)
    private BigDecimal precioTotalSinImpuesto;

    @Column(name = "codigo_impuesto", nullable = false, length = 2)
    private String codigoImpuesto = "2";

    @Column(name = "codigo_porcentaje", nullable = false, length = 4)
    private String codigoPorcentaje;

    @Column(name = "tarifa", nullable = false, precision = 5, scale = 2)
    private BigDecimal tarifa;

    @Column(name = "base_imponible", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseImponible;

    @Column(name = "valor_impuesto", nullable = false, precision = 14, scale = 2)
    private BigDecimal valorImpuesto;

    @Column(name = "creado_at", nullable = false, updatable = false)
    private Instant creadoAt = Instant.now();

    protected ComprobanteDetalle() {}

    public static ComprobanteDetalle nuevo(UUID empresaId, UUID comprobanteId, int orden,
                                            String codigoPrincipal, String descripcion,
                                            BigDecimal cantidad, BigDecimal precioUnitario,
                                            BigDecimal descuento, BigDecimal precioTotalSinImpuesto,
                                            CodigoPorcentajeIva iva,
                                            BigDecimal baseImponible, BigDecimal valorImpuesto) {
        ComprobanteDetalle d = new ComprobanteDetalle();
        d.empresaId = empresaId;
        d.comprobanteId = comprobanteId;
        d.orden = orden;
        d.codigoPrincipal = codigoPrincipal;
        d.descripcion = descripcion;
        d.cantidad = cantidad;
        d.precioUnitario = precioUnitario;
        d.descuento = descuento;
        d.precioTotalSinImpuesto = precioTotalSinImpuesto;
        d.codigoPorcentaje = iva.codigo;
        d.tarifa = iva.tarifa;
        d.baseImponible = baseImponible;
        d.valorImpuesto = valorImpuesto;
        return d;
    }

    public UUID getId() { return id; }
    public UUID getEmpresaId() { return empresaId; }
    public UUID getComprobanteId() { return comprobanteId; }
    public int getOrden() { return orden; }
    public String getCodigoPrincipal() { return codigoPrincipal; }
    public String getCodigoAuxiliar() { return codigoAuxiliar; }
    public String getDescripcion() { return descripcion; }
    public BigDecimal getCantidad() { return cantidad; }
    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public BigDecimal getDescuento() { return descuento; }
    public BigDecimal getPrecioTotalSinImpuesto() { return precioTotalSinImpuesto; }
    public String getCodigoImpuesto() { return codigoImpuesto; }
    public String getCodigoPorcentaje() { return codigoPorcentaje; }
    public BigDecimal getTarifa() { return tarifa; }
    public BigDecimal getBaseImponible() { return baseImponible; }
    public BigDecimal getValorImpuesto() { return valorImpuesto; }
    public Instant getCreadoAt() { return creadoAt; }

    public void setCodigoAuxiliar(String codigoAuxiliar) { this.codigoAuxiliar = codigoAuxiliar; }
}
