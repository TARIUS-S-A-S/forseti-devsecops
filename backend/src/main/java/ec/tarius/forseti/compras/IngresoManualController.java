package ec.tarius.forseti.compras;

import ec.tarius.forseti.compras.dto.ComprasDtos.AnularRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearIngresoManualRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.IngresoManualResponse;
import ec.tarius.forseti.compras.dto.ComprasDtos.MarcarCobradoRequest;
import ec.tarius.forseti.compras.service.IngresoManualService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingresos-manuales")
public class IngresoManualController {

    private final IngresoManualService service;

    public IngresoManualController(IngresoManualService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<IngresoManualResponse> listar(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.listar(desde, hasta).stream().map(service::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public IngresoManualResponse obtener(@PathVariable UUID id) {
        return service.toResponse(service.obtener(id));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<IngresoManualResponse> crear(@Valid @RequestBody CrearIngresoManualRequest req) {
        return ResponseEntity.ok(service.toResponse(service.crear(req)));
    }

    @PostMapping("/{id}/anular")
    @PreAuthorize("isAuthenticated()")
    public IngresoManualResponse anular(@PathVariable UUID id, @Valid @RequestBody AnularRequest req) {
        return service.toResponse(service.anular(id, req.motivo()));
    }

    @PostMapping("/{id}/marcar-cobrado")
    @PreAuthorize("isAuthenticated()")
    public IngresoManualResponse marcarCobrado(@PathVariable UUID id, @Valid @RequestBody MarcarCobradoRequest req) {
        return service.toResponse(service.marcarCobrado(id, req.fechaCobro()));
    }
}
