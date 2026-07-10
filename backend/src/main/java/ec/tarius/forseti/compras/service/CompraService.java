package ec.tarius.forseti.compras.service;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.compras.domain.Compra;
import ec.tarius.forseti.compras.domain.CompraAdjunto;
import ec.tarius.forseti.compras.domain.CompraCategoria;
import ec.tarius.forseti.compras.dto.ComprasDtos.CompraResponse;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearCompraRequest;
import ec.tarius.forseti.compras.repo.CompraAdjuntoRepository;
import ec.tarius.forseti.compras.repo.CompraCategoriaRepository;
import ec.tarius.forseti.compras.repo.CompraRepository;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio de compras. HU-F2 (alta manual + alta desde XML) · HU-F3 (adjuntos) ·
 * HU-F4 (totales mensuales) · HU-F6 (anular, NUNCA borrar).
 *
 * Defensa en profundidad RLS:
 *   - Toda escritura ocurre dentro de @Transactional → TenantTransactionAdvice setea
 *     {@code app.usuario_id} y {@code app.empresa_id} para que RLS Postgres filtre.
 *   - Los queries de duplicación incluyen ADEMÁS empresaId explícito en el WHERE
 *     (defensa en profundidad por si el aspect AOP falla — bug histórico documentado
 *     en MEMORY.md). NO se confía 100% en RLS.
 *
 * Anular NO hace DELETE: marca {@code anulada=true} con motivo + usuario + timestamp.
 * Para revertir una anulación: emitir un movimiento contrario, no "desanular".
 *
 * Race condition de duplicado:
 *   - El check pre-insert + save NO es atómico. Si 2 requests concurrentes pasan el
 *     check, el segundo cae en {@code UNIQUE} de DB. Capturamos {@link
 *     DataIntegrityViolationException} y la re-mapeamos a {@link ErrorCode#COMPRA_DUPLICADA}
 *     para devolver 409 al cliente en lugar de 500.
 */
@Service
public class CompraService {

    private static final Logger log = LoggerFactory.getLogger(CompraService.class);

    private final CompraRepository compraRepo;
    private final CompraAdjuntoRepository adjuntoRepo;
    private final CompraCategoriaRepository categoriaRepo;
    private final XmlCompraParser xmlParser;
    private final TikaFileValidator tikaValidator;

    public CompraService(CompraRepository compraRepo,
                         CompraAdjuntoRepository adjuntoRepo,
                         CompraCategoriaRepository categoriaRepo,
                         XmlCompraParser xmlParser,
                         TikaFileValidator tikaValidator) {
        this.compraRepo = compraRepo;
        this.adjuntoRepo = adjuntoRepo;
        this.categoriaRepo = categoriaRepo;
        this.xmlParser = xmlParser;
        this.tikaValidator = tikaValidator;
    }

    /** Crea una compra a partir de un form manual. HU-F2 (alta manual). */
    @Transactional
    public Compra crearManual(CrearCompraRequest req) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        UUID usuarioId = UsuarioActual.idObligatorio();

        // Detectar duplicado (mismo proveedor + tipo + nº dentro de la EMPRESA actual)
        Compra.TipoDocumento tipoDoc = Compra.TipoDocumento.valueOf(req.tipoDocumento());
        compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            empresaId, req.proveedorIdentificacion(), tipoDoc, req.numeroDocumento())
            .ifPresent(existing -> {
                throw new AppException(ErrorCode.COMPRA_DUPLICADA,
                    "Ya existe una compra con proveedor " + req.proveedorIdentificacion()
                    + " y nº " + req.numeroDocumento());
            });

        Compra c = Compra.nueva(empresaId, req.fechaEmision(),
            req.proveedorTipoId(), req.proveedorIdentificacion(), req.proveedorRazonSocial(),
            req.numeroDocumento(), req.concepto(),
            nvlZero(req.total()), Compra.Origen.MANUAL);
        c.setTipoDocumento(tipoDoc);
        c.bases(nvlZero(req.baseIva15()), nvlZero(req.baseIva0()),
                nvlZero(req.baseNoObjeto()), nvlZero(req.baseExento()),
                nvlZero(req.valorIva15()));
        c.retenciones(nvlZero(req.retencionIr()), nvlZero(req.retencionIva()));
        if (req.deducible() != null) c.setDeducible(req.deducible());
        if (req.categoriaId() != null) {
            categoriaRepo.findById(req.categoriaId())
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORIA_NO_ENCONTRADA,
                    "Categoría no encontrada: " + req.categoriaId()));
            c.asignarCategoria(req.categoriaId());
        }
        if (req.fechaPago() != null && req.formaPago() != null) {
            validarFechaPago(req.fechaPago(), req.fechaEmision());
            c.marcarPagado(req.fechaPago(), req.formaPago());
        }
        c.setCreadoPorUsuarioId(usuarioId);

        Compra guardada = saveCapturandoDuplicado(c, req.proveedorIdentificacion(), req.numeroDocumento());
        log.info("Compra MANUAL creada: id={} empresa={} proveedor={} total={}",
            guardada.getId(), empresaId, req.proveedorIdentificacion(), req.total());
        return guardada;
    }

    /**
     * Crea una compra a partir del XML SRI recibido (autollenado). Gate ① del Sprint 5.
     * Si ya existe una compra con el mismo (empresa, proveedor, tipo, número) la rechaza con 409.
     * Si el XML viene autorizado con {@code <autorizacion><fechaAutorizacion>}, persistimos
     * esa fecha REAL del SRI (no {@code Instant.now()} — eso sería mentir en BD).
     *
     * Parámetros opcionales: si el usuario quiere marcar el gasto como NO deducible o
     * con retenciones que no vienen en el XML, los pasa por acá.
     */
    @Transactional
    public Compra crearDesdeXml(byte[] xmlBytes, UUID categoriaId,
                                 Boolean deducible, BigDecimal retencionIr, BigDecimal retencionIva,
                                 String concepto) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        UUID usuarioId = UsuarioActual.idObligatorio();

        XmlCompraParser.DatosCompra datos;
        try {
            datos = xmlParser.parsear(xmlBytes);
        } catch (XmlCompraParser.XmlParserException e) {
            throw new AppException(ErrorCode.XML_COMPRA_INVALIDO,
                "No se pudo parsear el XML: " + e.getMessage());
        }

        Compra.TipoDocumento tipoDoc = Compra.TipoDocumento.valueOf(datos.tipoDocumento());
        compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            empresaId, datos.proveedorRuc(), tipoDoc, datos.numeroComprobante())
            .ifPresent(existing -> {
                throw new AppException(ErrorCode.COMPRA_DUPLICADA,
                    "Esta factura ya fue cargada (proveedor " + datos.proveedorRuc()
                    + ", nº " + datos.numeroComprobante() + ")");
            });

        String conceptoFinal = (concepto != null && !concepto.isBlank())
            ? concepto
            : datos.proveedorRazonSocial() + " — " + datos.numeroComprobante();
        Compra c = Compra.nueva(empresaId, datos.fechaEmision(),
            "04", datos.proveedorRuc(), datos.proveedorRazonSocial(),
            datos.numeroComprobante(), conceptoFinal,
            datos.importeTotal(), Compra.Origen.XML);
        c.setTipoDocumento(tipoDoc);
        c.bases(datos.baseIva15(), datos.baseIva0(), datos.baseNoObjeto(),
                datos.baseExento(), datos.valorIva15());
        // Retenciones del usuario (las del XML no las extrae el parser hoy)
        c.retenciones(nvlZero(retencionIr), nvlZero(retencionIva));
        if (deducible != null) c.setDeducible(deducible);
        // FechaAutorizacion REAL del SRI cuando viene en el envelope; caso contrario, ahora.
        Instant fechaAut = datos.fechaAutorizacion() != null ? datos.fechaAutorizacion() : Instant.now();
        c.cargarXmlSri(xmlBytes, datos.claveAcceso(), fechaAut);
        if (categoriaId != null) {
            categoriaRepo.findById(categoriaId)
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORIA_NO_ENCONTRADA,
                    "Categoría no encontrada: " + categoriaId));
            c.asignarCategoria(categoriaId);
        }
        c.setCreadoPorUsuarioId(usuarioId);

        Compra guardada = saveCapturandoDuplicado(c, datos.proveedorRuc(), datos.numeroComprobante());
        log.info("Compra XML cargada: id={} empresa={} proveedor={} clave={} fechaAutSri={}",
            guardada.getId(), empresaId, datos.proveedorRuc(), datos.claveAcceso(), fechaAut);
        return guardada;
    }

    @Transactional(readOnly = true)
    public Compra obtener(UUID id) {
        UsuarioActual.empresaActivaObligatoria();
        return compraRepo.findById(id)
            .orElseThrow(() -> new AppException(ErrorCode.COMPRA_NO_ENCONTRADA,
                "Compra no encontrada: " + id));
    }

    @Transactional(readOnly = true)
    public List<Compra> listar(LocalDate desde, LocalDate hasta) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        LocalDate d = desde != null ? desde : LocalDate.now().withDayOfMonth(1);
        LocalDate h = hasta != null ? hasta : LocalDate.now();
        return compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            empresaId, d, h);
    }

    /** Anular HU-F6. No borra; marca anulada=true con motivo + usuario + timestamp. */
    @Transactional
    public Compra anular(UUID id, String motivo) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        Compra c = obtener(id);
        if (c.isAnulada()) {
            throw new AppException(ErrorCode.COMPRA_YA_ANULADA,
                "Esta compra ya está anulada (motivo previo: " + c.getMotivoAnulacion() + ")");
        }
        c.anular(usuarioId, motivo);
        log.info("Compra ANULADA: id={} por={} motivo='{}'", id, usuarioId, motivo);
        return c;
    }

    /**
     * Marca la compra como pagada. Valida:
     *   - fechaPago no null + no posterior a hoy + no anterior a fechaEmision
     *   - formaPago no null/blank (código SRI de 2 caracteres)
     *   - compra no anulada
     */
    @Transactional
    public Compra marcarPagado(UUID id, LocalDate fechaPago, String formaPago) {
        if (fechaPago == null) {
            throw new AppException(ErrorCode.VALIDACION, "fechaPago es obligatoria");
        }
        if (formaPago == null || formaPago.isBlank()) {
            throw new AppException(ErrorCode.VALIDACION, "formaPago es obligatoria");
        }
        Compra c = obtener(id);
        if (c.isAnulada()) {
            throw new AppException(ErrorCode.COMPRA_YA_ANULADA,
                "No se puede pagar una compra anulada");
        }
        validarFechaPago(fechaPago, c.getFechaEmision());
        c.marcarPagado(fechaPago, formaPago);
        return c;
    }

    private static void validarFechaPago(LocalDate fechaPago, LocalDate fechaEmision) {
        LocalDate hoy = LocalDate.now();
        if (fechaPago.isAfter(hoy)) {
            throw new AppException(ErrorCode.VALIDACION,
                "fechaPago no puede ser futura (recibida " + fechaPago + ", hoy " + hoy + ")");
        }
        if (fechaEmision != null && fechaPago.isBefore(fechaEmision)) {
            throw new AppException(ErrorCode.VALIDACION,
                "fechaPago no puede ser anterior a fechaEmision (pago " + fechaPago
                + ", emisión " + fechaEmision + ")");
        }
    }

    // ─── Adjuntos (HU-F3) ───────────────────────────────────────────────

    @Transactional
    public CompraAdjunto agregarAdjunto(UUID compraId, byte[] contenido, String nombreOriginal) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        UUID usuarioId = UsuarioActual.idObligatorio();
        if (nombreOriginal == null || nombreOriginal.isBlank()) {
            throw new AppException(ErrorCode.ADJUNTO_INVALIDO,
                "El archivo debe tener un nombre con extensión .pdf o .xml");
        }
        Compra c = obtener(compraId);

        // Tika: detectar MIME real, rechazar extensión falsa (gate ④)
        String mimeReal;
        try {
            mimeReal = tikaValidator.validar(contenido, nombreOriginal);
        } catch (TikaFileValidator.UnsupportedAttachmentException e) {
            throw new AppException(ErrorCode.ADJUNTO_INVALIDO, e.getMessage());
        }

        String sha256 = sha256Hex(contenido);

        // Idempotencia: mismo archivo no se sube 2 veces a la misma compra (filtra por empresa
        // también — defensa en profundidad sobre RLS)
        Optional<CompraAdjunto> existe = adjuntoRepo.findByEmpresaIdAndCompraIdAndSha256(
            empresaId, compraId, sha256);
        if (existe.isPresent()) {
            throw new AppException(ErrorCode.ADJUNTO_DUPLICADO,
                "Este archivo ya está adjunto a la compra (mismo SHA-256)");
        }

        CompraAdjunto a = CompraAdjunto.nuevo(empresaId, c.getId(),
            nombreOriginal, mimeReal, contenido, sha256, usuarioId);
        CompraAdjunto guardado = adjuntoRepo.save(a);
        log.info("Adjunto agregado: compra={} archivo={} mime={} bytes={}",
            compraId, nombreOriginal, mimeReal, contenido.length);
        return guardado;
    }

    @Transactional(readOnly = true)
    public List<CompraAdjunto> listarAdjuntos(UUID compraId) {
        obtener(compraId);
        return adjuntoRepo.findByCompraIdOrderByCreadoAtAsc(compraId);
    }

    @Transactional(readOnly = true)
    public CompraAdjunto obtenerAdjunto(UUID adjuntoId) {
        UsuarioActual.empresaActivaObligatoria();
        return adjuntoRepo.findById(adjuntoId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO,
                "Adjunto no encontrado: " + adjuntoId));
    }

    // ─── Catálogo ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CompraCategoria> listarCategorias() {
        return categoriaRepo.findByActivaTrueOrderByOrden();
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static BigDecimal nvlZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /**
     * save() que captura la race condition del UNIQUE constraint. Si 2 requests concurrentes
     * pasan el check de duplicado pre-insert, el segundo cae acá → mapeamos a 409 (no 500).
     */
    private Compra saveCapturandoDuplicado(Compra c, String proveedor, String numero) {
        try {
            return compraRepo.save(c);
        } catch (DataIntegrityViolationException e) {
            String msg = e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage() : e.getMessage();
            if (msg != null && msg.contains("compra_empresa_id_proveedor_identificacion")) {
                throw new AppException(ErrorCode.COMPRA_DUPLICADA,
                    "Ya existe una compra con proveedor " + proveedor + " y nº " + numero
                    + " (race condition detectada)");
            }
            throw e;
        }
    }

    public CompraResponse toResponse(Compra c, String categoriaNombre, int adjuntosCount) {
        return new CompraResponse(
            c.getId(), c.getFechaEmision(),
            c.getProveedorTipoId(), c.getProveedorIdentificacion(), c.getProveedorRazonSocial(),
            c.getTipoDocumento().name(), c.getNumeroDocumento(), c.getClaveAcceso(),
            c.getConcepto(), c.getCategoriaId(), categoriaNombre,
            c.getBaseIva15(), c.getBaseIva0(), c.getBaseNoObjeto(), c.getBaseExento(),
            c.getValorIva15(), c.getRetencionIr(), c.getRetencionIva(), c.getTotal(),
            c.isDeducible(), c.getEstadoPago().name(), c.getFechaPago(), c.getFormaPago(),
            c.getOrigen().name(), c.isAnulada(), c.getAnuladaAt(), c.getMotivoAnulacion(),
            adjuntosCount, c.getCreadoAt());
    }
}
