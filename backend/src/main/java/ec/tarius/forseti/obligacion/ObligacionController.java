package ec.tarius.forseti.obligacion;

import ec.tarius.forseti.obligacion.domain.ObligacionCatalogo;
import ec.tarius.forseti.obligacion.domain.ObligacionEmpresa;
import ec.tarius.forseti.auth.UsuarioActual;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ObligacionController {

    private final ObligacionService service;

    public ObligacionController(ObligacionService service) {
        this.service = service;
    }

    @GetMapping("/obligaciones/catalogo")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ObligacionView>> catalogo() {
        return ResponseEntity.ok(service.catalogo().stream().map(this::toView).toList());
    }

    @GetMapping("/empresas/{empresaId}/obligaciones")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ObligacionEmpresaView>> activadas(@PathVariable UUID empresaId) {
        UsuarioActual.idObligatorio();
        return ResponseEntity.ok(
            service.activadasPorEmpresa(empresaId).stream().map(this::toEmpresaView).toList());
    }

    @GetMapping("/empresas/{empresaId}/obligaciones/sugeridas")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ObligacionView>> sugeridas(@PathVariable UUID empresaId) {
        UsuarioActual.idObligatorio();
        return ResponseEntity.ok(service.sugeridas(empresaId).stream().map(this::toView).toList());
    }

    @PostMapping("/empresas/{empresaId}/obligaciones/{codigo}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ObligacionEmpresaView> activar(@PathVariable UUID empresaId,
                                                          @PathVariable String codigo) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        return ResponseEntity.ok(toEmpresaView(service.activar(empresaId, codigo, usuarioId)));
    }

    @DeleteMapping("/empresas/{empresaId}/obligaciones/{codigo}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> desactivar(@PathVariable UUID empresaId,
                                            @PathVariable String codigo) {
        UsuarioActual.idObligatorio();
        service.desactivar(empresaId, codigo);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────

    public record ObligacionView(
        String codigo,
        String nombre,
        String descripcion,
        String categoria,
        String periodicidad,
        String reglaFecha,
        Map<String, Object> aplicaSi,
        boolean bloqueante,
        Integer[] alertaDias,
        int orden
    ) {}

    public record ObligacionEmpresaView(
        UUID id,
        UUID empresaId,
        String obligacionCodigo,
        boolean activa,
        Map<String, Object> config,
        Instant activadaAt
    ) {}

    private ObligacionView toView(ObligacionCatalogo o) {
        return new ObligacionView(o.getCodigo(), o.getNombre(), o.getDescripcion(),
            o.getCategoria().name(), o.getPeriodicidad().name(), o.getReglaFecha(),
            o.getAplicaSi(), o.isBloqueante(), o.getAlertaDias(), o.getOrden());
    }

    private ObligacionEmpresaView toEmpresaView(ObligacionEmpresa e) {
        return new ObligacionEmpresaView(e.getId(), e.getEmpresaId(), e.getObligacionCodigo(),
            e.isActiva(), e.getConfig(), e.getActivadaAt());
    }
}
