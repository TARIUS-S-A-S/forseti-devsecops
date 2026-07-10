package ec.tarius.forseti.emision;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteEvento;
import ec.tarius.forseti.emision.dto.EmisionDtos.ComprobanteDetalladoResponse;
import ec.tarius.forseti.emision.pdf.RideRenderer;
import ec.tarius.forseti.empresa.EmpresaRepository;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import ec.tarius.forseti.emision.dto.EmisionDtos.ComprobanteResponse;
import ec.tarius.forseti.emision.dto.EmisionDtos.DetalleResponse;
import ec.tarius.forseti.emision.dto.EmisionDtos.EmitirFacturaRequest;
import ec.tarius.forseti.emision.dto.EmisionDtos.EmitirNotaCreditoRequest;
import ec.tarius.forseti.emision.dto.EmisionDtos.EventoResponse;
import ec.tarius.forseti.emision.dto.EmisionDtos.ReceptorResponse;
import ec.tarius.forseti.emision.dto.EmisionDtos.TotalesResponse;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * API REST de emisión de comprobantes electrónicos.
 *
 * Tenant: el ambiente (PRUEBAS/PRODUCCION) y la empresa activa los infiere
 * EmisionService desde el TenantContext y los datos de la empresa. El front
 * no necesita pasarlos.
 */
@RestController
@RequestMapping("/api/v1/comprobantes")
public class EmisionController {

    private final EmisionService emisionService;
    private final ComprobanteRepository comprobanteRepo;
    private final ComprobanteDetalleRepository detalleRepo;
    private final ComprobanteEventoRepository eventoRepo;
    private final EmpresaRepository empresaRepo;
    private final RideRenderer rideRenderer;

    public EmisionController(EmisionService emisionService,
                              ComprobanteRepository comprobanteRepo,
                              ComprobanteDetalleRepository detalleRepo,
                              ComprobanteEventoRepository eventoRepo,
                              EmpresaRepository empresaRepo,
                              RideRenderer rideRenderer) {
        this.empresaRepo = empresaRepo;
        this.rideRenderer = rideRenderer;
        this.emisionService = emisionService;
        this.comprobanteRepo = comprobanteRepo;
        this.detalleRepo = detalleRepo;
        this.eventoRepo = eventoRepo;
    }

    /** Emite una factura. Devuelve el comprobante en estado FIRMADA (envío al SRI es async). */
    @PostMapping("/factura")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ComprobanteResponse> emitirFactura(
            @Valid @RequestBody EmitirFacturaRequest req,
            @RequestParam(name = "ambiente", defaultValue = "PRUEBAS") String ambiente) {
        UsuarioActual.idObligatorio();
        if (!"PRUEBAS".equals(ambiente) && !"PRODUCCION".equals(ambiente)) {
            throw new AppException(ErrorCode.VALIDACION,
                "ambiente debe ser PRUEBAS o PRODUCCION");
        }
        Comprobante c = emisionService.emitirFactura(req, ambiente);
        return ResponseEntity.ok(toResponse(c));
    }

    /**
     * Emite una NOTA DE CRÉDITO (codDoc=04) modificando un comprobante anterior.
     * Devuelve el comprobante en estado FIRMADA (envío al SRI es async).
     */
    @PostMapping("/nota-credito")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ComprobanteResponse> emitirNotaCredito(
            @Valid @RequestBody EmitirNotaCreditoRequest req,
            @RequestParam(name = "ambiente", defaultValue = "PRUEBAS") String ambiente) {
        UsuarioActual.idObligatorio();
        if (!"PRUEBAS".equals(ambiente) && !"PRODUCCION".equals(ambiente)) {
            throw new AppException(ErrorCode.VALIDACION, "ambiente debe ser PRUEBAS o PRODUCCION");
        }
        Comprobante c = emisionService.emitirNotaCredito(req, ambiente);
        return ResponseEntity.ok(toResponse(c));
    }

    /** Lista comprobantes de la empresa activa (RLS filtra). Opcional: ?estado=AUTORIZADA */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ResponseEntity<List<ComprobanteResponse>> listar(
            @RequestParam(required = false) String estado) {
        var empresaId = ec.tarius.forseti.shared.tenant.TenantContext.getEmpresaId();
        if (empresaId == null) {
            throw new AppException(ErrorCode.NO_AUTORIZADO, "Sin empresa activa");
        }
        List<Comprobante> comprobantes = estado != null
            ? comprobanteRepo.findByEmpresaIdAndEstadoOrderByCreadoAtDesc(
                empresaId, Comprobante.Estado.valueOf(estado))
            : comprobanteRepo.findByEmpresaIdOrderByCreadoAtDesc(empresaId);
        return ResponseEntity.ok(comprobantes.stream().map(this::toResponse).toList());
    }

    /**
     * Cancela un comprobante que aún no fue autorizado. Transiciona a ABANDONADA (terminal).
     * Body opcional con motivo libre. AUTORIZADAS no se cancelan acá — anulación SRI es Sprint 5.
     */
    @PostMapping("/{id}/cancelar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ComprobanteResponse> cancelar(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelarRequest req) {
        UsuarioActual.idObligatorio();
        String motivo = req != null ? req.motivo() : null;
        Comprobante c = emisionService.cancelar(id, motivo);
        return ResponseEntity.ok(toResponse(c));
    }

    public record CancelarRequest(String motivo) {}

    /** Detalle de un comprobante: cabecera + líneas + historia de eventos. */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ResponseEntity<ComprobanteDetalladoResponse> obtener(@PathVariable UUID id) {
        Comprobante c = comprobanteRepo.findById(id)
            .orElseThrow(() -> new AppException(ErrorCode.COMPROBANTE_NO_ENCONTRADO,
                "Comprobante no encontrado"));
        var detalles = detalleRepo.findByComprobanteIdOrderByOrdenAsc(c.getId())
            .stream().map(this::toDetalleResponse).toList();
        var historia = eventoRepo.findByComprobanteIdOrderByCreadoAtAsc(c.getId())
            .stream().map(this::toEventoResponse).toList();
        return ResponseEntity.ok(new ComprobanteDetalladoResponse(toResponse(c), detalles, historia));
    }

    /**
     * Descarga el RIDE (PDF) de un comprobante. Disponible en CUALQUIER estado a partir de
     * FIRMADA — offline-friendly: el cliente puede imprimir/entregar la factura ANTES de que
     * el SRI autorice. El PDF lleva marca de agua "PENDIENTE AUTORIZACIÓN" hasta que se
     * autoriza, momento en que cambia a verde con el número de autorización oficial.
     *
     * No se genera para BORRADOR (todavía no se firmó nada, los totales pueden ser cero).
     */
    @GetMapping(value = "/{id}/ride.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> ridePdf(@PathVariable UUID id) {
        Comprobante c = comprobanteRepo.findById(id)
            .orElseThrow(() -> new AppException(ErrorCode.COMPROBANTE_NO_ENCONTRADO,
                "Comprobante no encontrado"));
        if (c.getEstado() == Comprobante.Estado.BORRADOR) {
            throw new AppException(ErrorCode.COMPROBANTE_NO_ENCONTRADO,
                "El comprobante está en BORRADOR — no se puede generar RIDE aún");
        }
        Empresa empresa = empresaRepo.findById(c.getEmpresaId())
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA,
                "Empresa del comprobante no encontrada"));
        List<ComprobanteDetalle> detalles =
            detalleRepo.findByComprobanteIdOrderByOrdenAsc(c.getId());

        byte[] pdf = rideRenderer.renderizar(c, empresa, detalles);
        String filename = "RIDE-" + c.getNumeroComprobante().replace("-", "_") + ".pdf";

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
            .body(pdf);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Mappers
    // ─────────────────────────────────────────────────────────────────────

    private ComprobanteResponse toResponse(Comprobante c) {
        return new ComprobanteResponse(
            c.getId(),
            c.getEstablecimientoId(),
            c.getPuntoEmisionId(),
            c.getTipoComprobante(),
            c.getAmbiente(),
            c.getNumeroComprobante(),
            c.getClaveAcceso(),
            c.getFechaEmision(),
            c.getEstado().name(),
            new ReceptorResponse(
                c.getReceptorTipoId(), c.getReceptorIdentificacion(),
                c.getReceptorRazonSocial(), c.getReceptorDireccion(),
                c.getReceptorEmail(), c.getReceptorTelefono()),
            new TotalesResponse(
                c.getSubtotalSinImpuestos(), c.getTotalDescuento(),
                c.getTotalIva(), c.getImporteTotal(), c.getMoneda()),
            c.getFormaPago(),
            c.getPlazoDias(),
            c.getNumeroAutorizacion(),
            c.getFechaAutorizacion(),
            c.getMensajeSri(),
            c.getCodigoErrorSri(),
            c.getIntentosEnvio(),
            c.getCreadoAt());
    }

    private DetalleResponse toDetalleResponse(ec.tarius.forseti.emision.domain.ComprobanteDetalle d) {
        return new DetalleResponse(
            d.getOrden(), d.getCodigoPrincipal(), d.getCodigoAuxiliar(), d.getDescripcion(),
            d.getCantidad(), d.getPrecioUnitario(), d.getDescuento(),
            d.getPrecioTotalSinImpuesto(), d.getCodigoPorcentaje(),
            d.getTarifa(), d.getBaseImponible(), d.getValorImpuesto());
    }

    private EventoResponse toEventoResponse(ComprobanteEvento e) {
        return new EventoResponse(e.getCreadoAt(), e.getEstadoAnterior(),
            e.getEstadoNuevo(), e.getMensaje());
    }
}
