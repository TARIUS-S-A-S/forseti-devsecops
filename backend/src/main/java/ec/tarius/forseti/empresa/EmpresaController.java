package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.Empresa;
import ec.tarius.forseti.empresa.domain.PerfilTributario;
import ec.tarius.forseti.empresa.dto.EmpresaDtos.*;
import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/empresas")
public class EmpresaController {

    private final EmpresaService empresaService;
    private final EmpresaRepository empresaRepo;

    public EmpresaController(EmpresaService empresaService, EmpresaRepository empresaRepo) {
        this.empresaService = empresaService;
        this.empresaRepo = empresaRepo;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmpresaResponse> crear(@Valid @RequestBody CrearEmpresaRequest req) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        Empresa e = empresaService.crear(req, usuarioId);
        return ResponseEntity.ok(toResponse(e));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<EmpresaResponse>> listarMias() {
        UUID usuarioId = UsuarioActual.idObligatorio();
        var lista = empresaService.listarMias(usuarioId).stream().map(this::toResponse).toList();
        return ResponseEntity.ok(lista);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmpresaResponse> obtener(@PathVariable UUID id) {
        // RLS filtra automáticamente — si no es miembro, no la ve
        Empresa e = empresaRepo.findById(id)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA, "Empresa no encontrada"));
        return ResponseEntity.ok(toResponse(e));
    }

    @PostMapping("/seleccionar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> seleccionarActiva(@Valid @RequestBody SeleccionarEmpresaRequest req) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        empresaService.seleccionarEmpresaActiva(usuarioId, req.empresaId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/perfil-tributario")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PerfilTributarioResponse> actualizarPerfil(
            @PathVariable UUID id,
            @Valid @RequestBody ActualizarPerfilTributarioRequest req) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        PerfilTributario p = empresaService.actualizarPerfilTributario(id, req, usuarioId);
        return ResponseEntity.ok(toPerfilResponse(p));
    }

    @GetMapping("/{id}/perfil-tributario")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PerfilTributarioResponse> perfilVigente(@PathVariable UUID id) {
        PerfilTributario p = empresaService.perfilVigente(id);
        return ResponseEntity.ok(toPerfilResponse(p));
    }

    @GetMapping("/{id}/perfil-tributario/historial")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PerfilTributarioResponse>> historial(@PathVariable UUID id) {
        return ResponseEntity.ok(
            empresaService.historialPerfil(id).stream().map(this::toPerfilResponse).toList());
    }

    /**
     * Cambia el ambiente SRI default de la empresa. Pasar a PRODUCCION requiere validaciones
     * (cert activo, perfil vigente, al menos 1 secuencial PRODUCCION). Volver a PRUEBAS no.
     */
    @PutMapping("/{id}/ambiente")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmpresaResponse> cambiarAmbiente(@PathVariable UUID id,
                                                           @Valid @RequestBody CambiarAmbienteRequest req) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        Empresa e = empresaService.cambiarAmbiente(id, req.ambiente(), usuarioId);
        return ResponseEntity.ok(toResponse(e));
    }

    public record CambiarAmbienteRequest(@jakarta.validation.constraints.NotBlank String ambiente) {}

    /**
     * Archiva la empresa (soft-delete). Solo el DUEÑO. NO borra físicamente — preserva
     * trazabilidad legal (facturas, NCs, eventos). Se puede reactivar después.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmpresaResponse> archivar(@PathVariable UUID id) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        Empresa e = empresaService.archivar(id, usuarioId);
        return ResponseEntity.ok(toResponse(e));
    }

    /** Reactiva una empresa archivada. Solo el DUEÑO. */
    @PostMapping("/{id}/reactivar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<EmpresaResponse> reactivar(@PathVariable UUID id) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        Empresa e = empresaService.reactivar(id, usuarioId);
        return ResponseEntity.ok(toResponse(e));
    }

    // ─────────────────────────────────────────────────────────────
    // Mappers
    // ─────────────────────────────────────────────────────────────

    private EmpresaResponse toResponse(Empresa e) {
        return new EmpresaResponse(
            e.getId(), e.getRuc(), e.getRazonSocial(), e.getNombreComercial(),
            e.getTipoContribuyente(), e.getRegimenTributario(), e.getPeriodicidadIva(),
            e.isObligadoContabilidad(), e.isAgenteRetencion(),
            e.getDireccion(), e.getCiudad(), e.getProvincia(), e.getTelefono(), e.getEmail(),
            e.isActiva(), e.getAmbienteDefault(), e.getCodigoContribuyenteEspecial(),
            e.getCreadaAt());
    }

    private PerfilTributarioResponse toPerfilResponse(PerfilTributario p) {
        return new PerfilTributarioResponse(
            p.getId(), p.getEmpresaId(), p.getVigenteDesde(), p.getVigenteHasta(),
            p.getRegimenTributario(), p.getPeriodicidadIva(),
            p.isObligadoContabilidad(), p.isAgenteRetencion(),
            p.getMotivoCambio(), p.getCreadoAt());
    }
}
