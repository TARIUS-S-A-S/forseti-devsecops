package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.Establecimiento;
import ec.tarius.forseti.empresa.domain.PuntoEmision;
import ec.tarius.forseti.empresa.domain.Secuencial;
import ec.tarius.forseti.empresa.dto.EmpresaDtos.*;
import ec.tarius.forseti.auth.UsuarioActual;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/empresas/{empresaId}/establecimientos")
public class EstablecimientoController {

    private final EstablecimientoService service;

    public EstablecimientoController(EstablecimientoService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EstablecimientoResponse> crear(@PathVariable UUID empresaId,
                                                          @Valid @RequestBody CrearEstablecimientoRequest req) {
        UsuarioActual.idObligatorio();
        Establecimiento e = service.crear(empresaId, req);
        return ResponseEntity.ok(toResponse(e));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<EstablecimientoResponse>> listar(@PathVariable UUID empresaId) {
        UsuarioActual.idObligatorio();
        return ResponseEntity.ok(service.listar(empresaId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/{establecimientoId}/puntos-emision")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PuntoEmisionResponse> crearPunto(@PathVariable UUID empresaId,
                                                            @PathVariable UUID establecimientoId,
                                                            @Valid @RequestBody CrearPuntoEmisionRequest req) {
        UsuarioActual.idObligatorio();
        PuntoEmision p = service.crearPunto(empresaId, establecimientoId, req);
        return ResponseEntity.ok(toPuntoResponse(p));
    }

    @GetMapping("/{establecimientoId}/puntos-emision")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PuntoEmisionResponse>> listarPuntos(@PathVariable UUID empresaId,
                                                                    @PathVariable UUID establecimientoId) {
        UsuarioActual.idObligatorio();
        return ResponseEntity.ok(
            service.listarPuntos(establecimientoId).stream().map(this::toPuntoResponse).toList());
    }

    /**
     * Lista los secuenciales del punto de emisión, con su próximo número por tipo + ambiente.
     * Sirve para mostrar la tabla "Secuenciales" en la UI y elegir cuáles configurar.
     */
    @GetMapping("/{establecimientoId}/puntos-emision/{puntoEmisionId}/secuenciales")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SecuencialResponse>> listarSecuenciales(@PathVariable UUID empresaId,
                                                                       @PathVariable UUID establecimientoId,
                                                                       @PathVariable UUID puntoEmisionId) {
        UsuarioActual.idObligatorio();
        return ResponseEntity.ok(
            service.listarSecuenciales(puntoEmisionId).stream().map(this::toSecuencialResponse).toList());
    }

    /**
     * Configura el secuencial de (puntoEmision, tipo, ambiente). Si no existe lo crea; si
     * existe actualiza proximoNumero a ultimoNumeroEmitido + 1. Use case: migración desde
     * otro sistema que ya emitió N facturas, o configurar el de PRODUCCIÓN antes del switch.
     */
    @PutMapping("/{establecimientoId}/puntos-emision/{puntoEmisionId}/secuenciales")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SecuencialResponse> configurarSecuencial(@PathVariable UUID empresaId,
                                                                    @PathVariable UUID establecimientoId,
                                                                    @PathVariable UUID puntoEmisionId,
                                                                    @Valid @RequestBody ConfigurarSecuencialRequest req) {
        UsuarioActual.idObligatorio();
        Secuencial s = service.configurarSecuencial(empresaId, puntoEmisionId,
            Secuencial.TipoComprobante.valueOf(req.tipoComprobante()),
            Secuencial.Ambiente.valueOf(req.ambiente()),
            req.ultimoNumeroEmitido());
        return ResponseEntity.ok(toSecuencialResponse(s));
    }

    public record ConfigurarSecuencialRequest(
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "FACTURA|NOTA_CREDITO|NOTA_DEBITO|RETENCION|GUIA_REMISION|LIQUIDACION_COMPRA")
        String tipoComprobante,
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "PRUEBAS|PRODUCCION")
        String ambiente,
        @jakarta.validation.constraints.Min(0)
        long ultimoNumeroEmitido    // si 0, arranca desde 1
    ) {}

    public record SecuencialResponse(
        UUID id, UUID puntoEmisionId, String tipoComprobante, String ambiente, long proximoNumero
    ) {}

    private EstablecimientoResponse toResponse(Establecimiento e) {
        return new EstablecimientoResponse(e.getId(), e.getCodigo(), e.getNombre(), e.getDireccion(), e.isActivo());
    }

    private PuntoEmisionResponse toPuntoResponse(PuntoEmision p) {
        return new PuntoEmisionResponse(p.getId(), p.getEstablecimientoId(), p.getCodigo(),
            p.getDescripcion(), p.isActivo());
    }

    private SecuencialResponse toSecuencialResponse(Secuencial s) {
        return new SecuencialResponse(s.getId(), s.getPuntoEmisionId(),
            s.getTipoComprobante().name(), s.getAmbiente().name(), s.getProximoNumero());
    }
}
