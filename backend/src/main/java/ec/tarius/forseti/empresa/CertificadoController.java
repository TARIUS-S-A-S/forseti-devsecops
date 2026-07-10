package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.CertificadoFirma;
import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/empresas/{empresaId}/certificado")
public class CertificadoController {

    private static final long MAX_P12_BYTES = 256 * 1024; // 256 KB es generoso para un .p12

    private final CertificadoService service;

    public CertificadoController(CertificadoService service) {
        this.service = service;
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CertificadoView> cargar(@PathVariable UUID empresaId,
                                                    @RequestPart("p12") MultipartFile p12,
                                                    @RequestPart("password") String password) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        if (p12.getSize() > MAX_P12_BYTES) {
            throw new AppException(ErrorCode.CERTIFICADO_INVALIDO,
                "Archivo demasiado grande (máx 256 KB)");
        }
        byte[] bytes;
        try {
            bytes = p12.getBytes();
        } catch (IOException e) {
            throw new AppException(ErrorCode.CERTIFICADO_INVALIDO, "No se pudo leer el archivo");
        }
        CertificadoFirma cert = service.cargar(empresaId, bytes, password, usuarioId);
        return ResponseEntity.ok(toView(cert));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CertificadoView> activo(@PathVariable UUID empresaId) {
        UsuarioActual.idObligatorio();
        return service.activo(empresaId)
            .map(this::toView)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Historial de certs de la empresa (activos + desactivados), del más reciente al más viejo.
     * Útil para auditar qué cert firmó qué factura, y para preparar la rotación anual de firma.
     */
    @GetMapping("/historial")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.List<CertificadoView>> historial(@PathVariable UUID empresaId) {
        UsuarioActual.idObligatorio();
        return ResponseEntity.ok(service.historial(empresaId).stream().map(this::toView).toList());
    }

    /**
     * PUT = reemplazar el cert activo. Internamente desactiva el anterior y carga el nuevo.
     * Mismos parámetros que POST (alias por idempotencia REST). El usuario al renovar la
     * firma del proveedor usa este endpoint y la UI muestra "Reemplazar firma".
     */
    @PutMapping(consumes = "multipart/form-data")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CertificadoView> reemplazar(@PathVariable UUID empresaId,
                                                       @RequestPart("p12") MultipartFile p12,
                                                       @RequestPart("password") String password) {
        return cargar(empresaId, p12, password);
    }

    /**
     * Desactiva el cert activo de la empresa SIN cargar uno nuevo. NO borra el registro
     * (se preserva la referencia histórica). Después de esto, la empresa no puede emitir
     * facturas hasta cargar uno nuevo.
     *
     * Idempotente: si no hay cert activo, devuelve 204 igual.
     */
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> desactivar(@PathVariable UUID empresaId) {
        UsuarioActual.idObligatorio();
        service.desactivar(empresaId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Promueve un cert inactivo (cargado antes) a activo. Si hay otro activo, lo desactiva
     * en la misma TX. Sirve para "elegir cuál cert usar" cuando hay varios cargados
     * (típico: Lazzate viejo + Security Data nuevo, y querés cambiar el activo sin
     * recargar el .p12).
     */
    @PostMapping("/{certificadoId}/activar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CertificadoView> activar(@PathVariable UUID empresaId,
                                                     @PathVariable UUID certificadoId) {
        UsuarioActual.idObligatorio();
        return ResponseEntity.ok(toView(service.activar(empresaId, certificadoId)));
    }

    /**
     * Eliminación definitiva del cert. SOLO permitida si el cert nunca firmó comprobantes
     * (preserva trazabilidad legal). Si tiene comprobantes, devuelve 409 con mensaje claro
     * — la UI muestra "no se puede eliminar, solo desactivar".
     */
    @DeleteMapping("/{certificadoId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> eliminar(@PathVariable UUID empresaId,
                                          @PathVariable UUID certificadoId) {
        UsuarioActual.idObligatorio();
        service.eliminar(empresaId, certificadoId);
        return ResponseEntity.noContent().build();
    }

    /** Vista pública — NUNCA incluye el blob ni la password. */
    public record CertificadoView(
        UUID id,
        String sujetoCn,
        String emisorCn,
        String numeroSerie,
        Instant vigenteDesde,
        Instant vigenteHasta,
        boolean activo,
        Instant cargadoAt,
        long diasParaCaducar
    ) {}

    private CertificadoView toView(CertificadoFirma c) {
        long dias = (c.getVigenteHasta().toEpochMilli() - Instant.now().toEpochMilli()) / (1000L * 60 * 60 * 24);
        return new CertificadoView(
            c.getId(), c.getSujetoCn(), c.getEmisorCn(), c.getNumeroSerie(),
            c.getVigenteDesde(), c.getVigenteHasta(), c.isActivo(), c.getCargadoAt(), dias);
    }
}
