package ec.tarius.forseti.emision.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class EmisionDtos {

    private EmisionDtos() {}

    // ─────────────────────────────────────────────────────────────────────
    // Input — crear factura
    // ─────────────────────────────────────────────────────────────────────

    public record EmitirFacturaRequest(
        @NotNull UUID establecimientoId,
        @NotNull UUID puntoEmisionId,
        LocalDate fechaEmision,                            // null = hoy
        @NotNull @Valid Receptor receptor,
        @NotEmpty @Valid List<Item> items,
        @Pattern(regexp = "01|15|16|17|18|19|20|21") String formaPago,  // null = "01"
        @Min(0) Integer plazoDias                          // null = 0
    ) {}

    /** Request para emitir una Nota de Crédito sobre un comprobante anterior. */
    public record EmitirNotaCreditoRequest(
        @NotNull UUID establecimientoId,
        @NotNull UUID puntoEmisionId,
        LocalDate fechaEmision,                            // null = hoy
        @NotNull @Valid Receptor receptor,
        @NotEmpty @Valid List<Item> items,
        @NotBlank @Pattern(regexp = "01|03|04|05") String docModificadoTipo,  // típicamente "01"
        @NotBlank @Size(max = 17) String docModificadoNumero,                  // 001-001-000000001
        @NotNull LocalDate docModificadoFecha,
        @NotBlank @Size(min = 5, max = 300) String motivo
    ) {}

    public record Receptor(
        @NotBlank @Pattern(regexp = "04|05|06|07|08") String tipoId,
        @NotBlank @Size(max = 20) String identificacion,
        @NotBlank @Size(max = 300) String razonSocial,
        @Size(max = 500) String direccion,
        @Email @Size(max = 200) String email,
        @Size(max = 30) String telefono
    ) {}

    public record Item(
        @NotBlank @Size(max = 25) String codigoPrincipal,
        @Size(max = 25) String codigoAuxiliar,
        @NotBlank @Size(max = 300) String descripcion,
        @NotNull @DecimalMin(value = "0.000001", message = "cantidad > 0") BigDecimal cantidad,
        @NotNull @DecimalMin("0.00") BigDecimal precioUnitario,
        @DecimalMin("0.00") BigDecimal descuento,          // null = 0
        @NotBlank @Pattern(regexp = "IVA_0|IVA_15|NO_OBJETO|EXENTO") String codigoIva
    ) {}

    // ─────────────────────────────────────────────────────────────────────
    // Output — comprobante
    // ─────────────────────────────────────────────────────────────────────

    public record ComprobanteResponse(
        UUID id,
        UUID establecimientoId,
        UUID puntoEmisionId,
        String tipoComprobante,
        String ambiente,
        String numeroComprobante,
        String claveAcceso,
        LocalDate fechaEmision,
        String estado,
        ReceptorResponse receptor,
        TotalesResponse totales,
        String formaPago,
        Integer plazoDias,
        String numeroAutorizacion,
        Instant fechaAutorizacion,
        String mensajeSri,
        String codigoErrorSri,
        Integer intentosEnvio,
        Instant creadoAt
    ) {}

    public record ReceptorResponse(
        String tipoId, String identificacion, String razonSocial,
        String direccion, String email, String telefono
    ) {}

    public record TotalesResponse(
        BigDecimal subtotalSinImpuestos,
        BigDecimal totalDescuento,
        BigDecimal totalIva,
        BigDecimal importeTotal,
        String moneda
    ) {}

    public record DetalleResponse(
        int orden, String codigoPrincipal, String codigoAuxiliar, String descripcion,
        BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal descuento,
        BigDecimal precioTotalSinImpuesto, String codigoIva,
        BigDecimal tarifa, BigDecimal baseImponible, BigDecimal valorImpuesto
    ) {}

    public record EventoResponse(
        Instant cuando, String estadoAnterior, String estadoNuevo, String mensaje
    ) {}

    public record ComprobanteDetalladoResponse(
        ComprobanteResponse cabecera,
        List<DetalleResponse> detalles,
        List<EventoResponse> historia
    ) {}
}
