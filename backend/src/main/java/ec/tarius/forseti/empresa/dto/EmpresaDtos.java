package ec.tarius.forseti.empresa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class EmpresaDtos {

    private EmpresaDtos() {}

    public record CrearEmpresaRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{13}$", message = "RUC debe tener 13 dígitos numéricos") String ruc,
        @NotBlank @Size(min = 3, max = 300) String razonSocial,
        @Size(max = 300) String nombreComercial,
        @NotBlank @Pattern(regexp = "PN|SA|SAS|LTDA|EP|OTRO") String tipoContribuyente,
        @NotBlank @Pattern(regexp = "RIMPE_NP|RIMPE_EMPRENDEDOR|GENERAL") String regimenTributario,
        @NotBlank @Pattern(regexp = "MENSUAL|SEMESTRAL|NO_APLICA") String periodicidadIva,
        boolean obligadoContabilidad,
        boolean agenteRetencion,
        @Size(max = 500) String direccion,
        @Size(max = 100) String ciudad,
        @Size(max = 100) String provincia,
        @Size(max = 30) String telefono,
        @Size(max = 200) String email
    ) {}

    public record EmpresaResponse(
        UUID id,
        String ruc,
        String razonSocial,
        String nombreComercial,
        String tipoContribuyente,
        String regimenTributario,
        String periodicidadIva,
        boolean obligadoContabilidad,
        boolean agenteRetencion,
        String direccion,
        String ciudad,
        String provincia,
        String telefono,
        String email,
        boolean activa,
        String ambienteDefault,
        String codigoContribuyenteEspecial,
        Instant creadaAt
    ) {}

    public record ActualizarPerfilTributarioRequest(
        @NotBlank @Pattern(regexp = "RIMPE_NP|RIMPE_EMPRENDEDOR|GENERAL") String regimenTributario,
        @NotBlank @Pattern(regexp = "MENSUAL|SEMESTRAL|NO_APLICA") String periodicidadIva,
        boolean obligadoContabilidad,
        boolean agenteRetencion,
        LocalDate vigenteDesde,
        @Size(max = 500) String motivoCambio
    ) {}

    public record PerfilTributarioResponse(
        UUID id,
        UUID empresaId,
        LocalDate vigenteDesde,
        LocalDate vigenteHasta,
        String regimenTributario,
        String periodicidadIva,
        boolean obligadoContabilidad,
        boolean agenteRetencion,
        String motivoCambio,
        Instant creadoAt
    ) {}

    public record CrearEstablecimientoRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{3}$") String codigo,
        @Size(max = 200) String nombre,
        @Size(max = 500) String direccion
    ) {}

    public record EstablecimientoResponse(
        UUID id,
        String codigo,
        String nombre,
        String direccion,
        boolean activo
    ) {}

    public record CrearPuntoEmisionRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{3}$") String codigo,
        @Size(max = 200) String descripcion
    ) {}

    public record PuntoEmisionResponse(
        UUID id,
        UUID establecimientoId,
        String codigo,
        String descripcion,
        boolean activo
    ) {}

    public record SeleccionarEmpresaRequest(
        @jakarta.validation.constraints.NotNull UUID empresaId
    ) {}
}
