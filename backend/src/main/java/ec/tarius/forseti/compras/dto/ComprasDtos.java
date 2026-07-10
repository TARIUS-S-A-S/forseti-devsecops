package ec.tarius.forseti.compras.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ComprasDtos {

    private ComprasDtos() {}

    public record CrearCompraRequest(
        @NotNull LocalDate fechaEmision,
        @NotBlank @Pattern(regexp = "04|05|06|08") String proveedorTipoId,
        @NotBlank @Size(max = 20) String proveedorIdentificacion,
        @NotBlank @Size(max = 300) String proveedorRazonSocial,
        @NotBlank @Pattern(regexp = "FACTURA|NOTA_CREDITO|NOTA_DEBITO|LIQUIDACION_COMPRA|RECIBO|OTRO") String tipoDocumento,
        @NotBlank @Size(max = 50) String numeroDocumento,
        @NotBlank @Size(max = 500) String concepto,
        UUID categoriaId,
        @PositiveOrZero BigDecimal baseIva15,
        @PositiveOrZero BigDecimal baseIva0,
        @PositiveOrZero BigDecimal baseNoObjeto,
        @PositiveOrZero BigDecimal baseExento,
        @PositiveOrZero BigDecimal valorIva15,
        @PositiveOrZero BigDecimal retencionIr,
        @PositiveOrZero BigDecimal retencionIva,
        @PositiveOrZero BigDecimal total,
        Boolean deducible,
        String formaPago,
        LocalDate fechaPago
    ) {}

    public record CompraResponse(
        UUID id,
        LocalDate fechaEmision,
        String proveedorTipoId,
        String proveedorIdentificacion,
        String proveedorRazonSocial,
        String tipoDocumento,
        String numeroDocumento,
        String claveAcceso,
        String concepto,
        UUID categoriaId,
        String categoriaNombre,
        BigDecimal baseIva15,
        BigDecimal baseIva0,
        BigDecimal baseNoObjeto,
        BigDecimal baseExento,
        BigDecimal valorIva15,
        BigDecimal retencionIr,
        BigDecimal retencionIva,
        BigDecimal total,
        boolean deducible,
        String estadoPago,
        LocalDate fechaPago,
        String formaPago,
        String origen,
        boolean anulada,
        Instant anuladaAt,
        String motivoAnulacion,
        int adjuntosCount,
        Instant creadoAt
    ) {}

    public record AnularRequest(
        @NotBlank @Size(min = 3, max = 500) String motivo
    ) {}

    public record MarcarPagadoRequest(
        @NotNull LocalDate fechaPago,
        @NotBlank @Size(max = 2) String formaPago
    ) {}

    public record CategoriaResponse(
        UUID id,
        String codigo,
        String nombre,
        String descripcion,
        int orden
    ) {}

    public record AdjuntoResponse(
        UUID id,
        String nombreOriginal,
        String mimeTypeReal,
        int tamanoBytes,
        String sha256,
        Instant creadoAt
    ) {}

    // ─── Ingreso Manual ───────────────────────────────────────────────────

    public record CrearIngresoManualRequest(
        @NotNull LocalDate fechaEmision,
        @Size(max = 20) String clienteIdentificacion,
        @NotBlank @Size(max = 300) String clienteRazonSocial,
        @NotBlank @Size(max = 500) String concepto,
        @PositiveOrZero BigDecimal baseIva15,
        @PositiveOrZero BigDecimal baseIva0,
        @PositiveOrZero BigDecimal valorIva15,
        @PositiveOrZero BigDecimal retencionRecibida,
        @PositiveOrZero BigDecimal total,
        LocalDate fechaCobro
    ) {}

    public record IngresoManualResponse(
        UUID id,
        LocalDate fechaEmision,
        String clienteIdentificacion,
        String clienteRazonSocial,
        String concepto,
        BigDecimal baseIva15,
        BigDecimal baseIva0,
        BigDecimal valorIva15,
        BigDecimal retencionRecibida,
        BigDecimal total,
        String estadoCobro,
        LocalDate fechaCobro,
        boolean anulada,
        Instant anuladaAt,
        String motivoAnulacion,
        Instant creadoAt
    ) {}

    public record MarcarCobradoRequest(
        @NotNull LocalDate fechaCobro
    ) {}

    // ─── Flujo de caja / Exports ──────────────────────────────────────────

    public record FlujoCajaResponse(
        LocalDate desde,
        LocalDate hasta,
        BigDecimal totalIngresosCobrados,
        BigDecimal totalIngresosPendientes,
        BigDecimal totalEgresosPagados,
        BigDecimal totalEgresosPendientes,
        BigDecimal saldoCobradoMenosPagado
    ) {}
}
