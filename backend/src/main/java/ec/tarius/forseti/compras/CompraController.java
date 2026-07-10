package ec.tarius.forseti.compras;

import ec.tarius.forseti.compras.domain.Compra;
import ec.tarius.forseti.compras.domain.CompraAdjunto;
import ec.tarius.forseti.compras.domain.CompraCategoria;
import ec.tarius.forseti.compras.dto.ComprasDtos.AdjuntoResponse;
import ec.tarius.forseti.compras.dto.ComprasDtos.AnularRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.CategoriaResponse;
import ec.tarius.forseti.compras.dto.ComprasDtos.CompraResponse;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearCompraRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.MarcarPagadoRequest;
import ec.tarius.forseti.compras.repo.CompraAdjuntoRepository;
import ec.tarius.forseti.compras.repo.CompraAdjuntoRepository.CompraAdjuntoCount;
import ec.tarius.forseti.compras.repo.CompraCategoriaRepository;
import ec.tarius.forseti.compras.service.CompraService;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/compras")
public class CompraController {

    private final CompraService compraService;
    private final CompraAdjuntoRepository adjuntoRepo;
    private final CompraCategoriaRepository categoriaRepo;

    public CompraController(CompraService compraService,
                             CompraAdjuntoRepository adjuntoRepo,
                             CompraCategoriaRepository categoriaRepo) {
        this.compraService = compraService;
        this.adjuntoRepo = adjuntoRepo;
        this.categoriaRepo = categoriaRepo;
    }

    /**
     * Lista compras del período. Sin N+1: una sola query para conteos de adjuntos
     * (agrupada por compraId) y un fetchAll de categorías referenciadas.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CompraResponse> listar(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        List<Compra> compras = compraService.listar(desde, hasta);
        if (compras.isEmpty()) return List.of();

        // 1 query: conteos agrupados de adjuntos por compraId
        List<UUID> compraIds = compras.stream().map(Compra::getId).toList();
        Map<UUID, Long> adjuntosPorCompra = adjuntoRepo.countByCompraIds(compraIds).stream()
            .collect(Collectors.toMap(CompraAdjuntoCount::getCompraId, CompraAdjuntoCount::getTotal));

        // 1 query: categorías referenciadas (findAllById es 1 SELECT IN)
        List<UUID> categoriaIds = compras.stream()
            .map(Compra::getCategoriaId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> nombrePorCategoria = categoriaIds.isEmpty() ? Map.of()
            : categoriaRepo.findAllById(categoriaIds).stream()
                .collect(Collectors.toMap(CompraCategoria::getId, CompraCategoria::getNombre));

        return compras.stream()
            .map(c -> compraService.toResponse(c,
                c.getCategoriaId() != null ? nombrePorCategoria.get(c.getCategoriaId()) : null,
                adjuntosPorCompra.getOrDefault(c.getId(), 0L).intValue()))
            .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public CompraResponse obtener(@PathVariable UUID id) {
        Compra c = compraService.obtener(id);
        return compraService.toResponse(c, nombreCategoria(c.getCategoriaId()),
            adjuntoRepo.findByCompraIdOrderByCreadoAtAsc(c.getId()).size());
    }

    /** Alta manual de compra (HU-F2 form). */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CompraResponse> crearManual(@Valid @RequestBody CrearCompraRequest req) {
        Compra c = compraService.crearManual(req);
        return ResponseEntity.ok(compraService.toResponse(c, nombreCategoria(c.getCategoriaId()), 0));
    }

    /**
     * Alta desde XML SRI recibido (HU-F2 autollenado). Gate ①.
     * Soporta marcar el gasto como NO deducible y agregar retenciones/concepto custom
     * cuando el XML no los trae (típicamente porque el sistema del proveedor no los emitió).
     */
    @PostMapping(value = "/xml", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CompraResponse> crearDesdeXml(
            @RequestParam("xml") MultipartFile xml,
            @RequestParam(value = "categoriaId", required = false) UUID categoriaId,
            @RequestParam(value = "deducible", required = false) Boolean deducible,
            @RequestParam(value = "retencionIr", required = false) BigDecimal retencionIr,
            @RequestParam(value = "retencionIva", required = false) BigDecimal retencionIva,
            @RequestParam(value = "concepto", required = false) String concepto) {
        if (xml.isEmpty()) {
            throw new AppException(ErrorCode.XML_COMPRA_INVALIDO, "El archivo XML viene vacío");
        }
        try {
            Compra c = compraService.crearDesdeXml(xml.getBytes(), categoriaId,
                deducible, retencionIr, retencionIva, concepto);
            return ResponseEntity.ok(compraService.toResponse(c, nombreCategoria(c.getCategoriaId()), 0));
        } catch (IOException e) {
            throw new AppException(ErrorCode.XML_COMPRA_INVALIDO,
                "No se pudo leer el archivo: " + e.getMessage());
        }
    }

    /** Anular HU-F6 (NO borra, deja huella). */
    @PostMapping("/{id}/anular")
    @PreAuthorize("isAuthenticated()")
    public CompraResponse anular(@PathVariable UUID id, @Valid @RequestBody AnularRequest req) {
        Compra c = compraService.anular(id, req.motivo());
        return compraService.toResponse(c, nombreCategoria(c.getCategoriaId()),
            adjuntoRepo.findByCompraIdOrderByCreadoAtAsc(c.getId()).size());
    }

    @PostMapping("/{id}/marcar-pagado")
    @PreAuthorize("isAuthenticated()")
    public CompraResponse marcarPagado(@PathVariable UUID id, @Valid @RequestBody MarcarPagadoRequest req) {
        Compra c = compraService.marcarPagado(id, req.fechaPago(), req.formaPago());
        return compraService.toResponse(c, nombreCategoria(c.getCategoriaId()),
            adjuntoRepo.findByCompraIdOrderByCreadoAtAsc(c.getId()).size());
    }

    // ─── Adjuntos (HU-F3) ─────────────────────────────────────────────

    @PostMapping(value = "/{id}/adjuntos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AdjuntoResponse> subirAdjunto(
            @PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        try {
            CompraAdjunto a = compraService.agregarAdjunto(id, file.getBytes(), file.getOriginalFilename());
            return ResponseEntity.ok(new AdjuntoResponse(
                a.getId(), a.getNombreOriginal(), a.getMimeTypeReal(),
                a.getTamanoBytes(), a.getSha256(), a.getCreadoAt()));
        } catch (IOException e) {
            throw new AppException(ErrorCode.ADJUNTO_INVALIDO,
                "No se pudo leer el archivo: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/adjuntos")
    @PreAuthorize("isAuthenticated()")
    public List<AdjuntoResponse> listarAdjuntos(@PathVariable UUID id) {
        return compraService.listarAdjuntos(id).stream()
            .map(a -> new AdjuntoResponse(
                a.getId(), a.getNombreOriginal(), a.getMimeTypeReal(),
                a.getTamanoBytes(), a.getSha256(), a.getCreadoAt()))
            .toList();
    }

    @GetMapping("/{compraId}/adjuntos/{adjuntoId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> descargarAdjunto(
            @PathVariable UUID compraId, @PathVariable UUID adjuntoId) {
        compraService.obtener(compraId); // valida que la compra existe + RLS
        CompraAdjunto a = compraService.obtenerAdjunto(adjuntoId);
        if (!a.getCompraId().equals(compraId)) {
            throw new AppException(ErrorCode.NO_ENCONTRADO, "Adjunto no pertenece a la compra");
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(a.getMimeTypeReal()));
        // ContentDisposition.attachment (NO formData — esto último es para multipart form-data)
        h.setContentDisposition(ContentDisposition.attachment()
            .filename(a.getNombreOriginal(), StandardCharsets.UTF_8)
            .build());
        return ResponseEntity.ok().headers(h).body(a.getContenido());
    }

    // ─── Catálogo ─────────────────────────────────────────────────────

    @GetMapping("/categorias")
    @PreAuthorize("isAuthenticated()")
    public List<CategoriaResponse> categorias() {
        return compraService.listarCategorias().stream()
            .map(c -> new CategoriaResponse(c.getId(), c.getCodigo(), c.getNombre(),
                                             c.getDescripcion(), c.getOrden()))
            .toList();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    /** Resuelve el nombre de UNA categoría — usar para responses single (alta/detalle). */
    private String nombreCategoria(UUID categoriaId) {
        if (categoriaId == null) return null;
        return categoriaRepo.findById(categoriaId).map(CompraCategoria::getNombre).orElse(null);
    }
}
