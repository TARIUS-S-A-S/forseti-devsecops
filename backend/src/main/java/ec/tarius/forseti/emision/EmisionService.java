package ec.tarius.forseti.emision;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.Comprobante.Estado;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle.CodigoPorcentajeIva;
import ec.tarius.forseti.emision.domain.ComprobanteEvento;
import ec.tarius.forseti.emision.dto.EmisionDtos;
import ec.tarius.forseti.emision.dto.EmisionDtos.EmitirFacturaRequest;
import ec.tarius.forseti.emision.jobs.EnviarComprobanteRequest;
import ec.tarius.forseti.emision.dto.EmisionDtos.Item;
import ec.tarius.forseti.emision.sri.ClaveAccesoGenerator;
import ec.tarius.forseti.emision.sri.FacturaXmlBuilder;
import ec.tarius.forseti.emision.sri.NotaCreditoXmlBuilder;
import ec.tarius.forseti.emision.sri.XadesSigner;
import ec.tarius.forseti.emision.sri.XsdValidator;
import ec.tarius.forseti.empresa.CertificadoFirmaRepository;
import ec.tarius.forseti.empresa.CertificadoService;
import ec.tarius.forseti.empresa.EmpresaRepository;
import ec.tarius.forseti.empresa.EstablecimientoRepository;
import ec.tarius.forseti.empresa.PuntoEmisionRepository;
import ec.tarius.forseti.empresa.SecuencialRepository;
import ec.tarius.forseti.empresa.domain.CertificadoFirma;
import ec.tarius.forseti.empresa.domain.Empresa;
import ec.tarius.forseti.empresa.domain.Establecimiento;
import ec.tarius.forseti.empresa.domain.PuntoEmision;
import ec.tarius.forseti.empresa.domain.Secuencial;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import ec.tarius.forseti.shared.tenant.TenantContext;
import org.jobrunr.scheduling.BackgroundJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orquesta la emisión de un comprobante electrónico SRI (Sprint 3, HU-F9 + HU-F7 parte 1).
 *
 * Flow @Transactional:
 *   1. Validar establecimiento + punto_emision (pertenencia a la empresa activa)
 *   2. Obtener certificado activo (lanza si no hay)
 *   3. Asignar secuencial transaccional (FOR UPDATE → gate Sprint 3 ③: 100 paralelas sin saltos)
 *   4. Generar clave de acceso 49 dígitos
 *   5. Calcular totales desde los items
 *   6. Crear Comprobante (BORRADOR) + ComprobanteDetalles
 *   7. Generar XML factura v2.1, validar XSD oficial
 *   8. Firmar XAdES-BES (CertificadoService descifra .p12 + XadesSigner firma)
 *   9. Persistir xml_firmado + transicionar a FIRMADA + registrar evento
 *
 * El envío al SRI lo hace el worker JobRunr (B.4): toma todos los FIRMADA pendientes
 * y los procesa async — esto desacopla la latencia del SRI del request del usuario.
 */
@Service
public class EmisionService {

    private static final Logger log = LoggerFactory.getLogger(EmisionService.class);

    private final EmpresaRepository empresaRepo;
    private final EstablecimientoRepository establecimientoRepo;
    private final PuntoEmisionRepository puntoEmisionRepo;
    private final SecuencialRepository secuencialRepo;
    private final CertificadoFirmaRepository certificadoRepo;
    private final CertificadoService certificadoService;
    private final ComprobanteRepository comprobanteRepo;
    private final ComprobanteDetalleRepository detalleRepo;
    private final ComprobanteEventoRepository eventoRepo;
    private final XsdValidator xsdValidator;
    private final XadesSigner xadesSigner;
    private final AuditLogger audit;

    public EmisionService(EmpresaRepository empresaRepo,
                           EstablecimientoRepository establecimientoRepo,
                           PuntoEmisionRepository puntoEmisionRepo,
                           SecuencialRepository secuencialRepo,
                           CertificadoFirmaRepository certificadoRepo,
                           CertificadoService certificadoService,
                           ComprobanteRepository comprobanteRepo,
                           ComprobanteDetalleRepository detalleRepo,
                           ComprobanteEventoRepository eventoRepo,
                           XsdValidator xsdValidator,
                           XadesSigner xadesSigner,
                           AuditLogger audit) {
        this.empresaRepo = empresaRepo;
        this.establecimientoRepo = establecimientoRepo;
        this.puntoEmisionRepo = puntoEmisionRepo;
        this.secuencialRepo = secuencialRepo;
        this.certificadoRepo = certificadoRepo;
        this.certificadoService = certificadoService;
        this.comprobanteRepo = comprobanteRepo;
        this.detalleRepo = detalleRepo;
        this.eventoRepo = eventoRepo;
        this.xsdValidator = xsdValidator;
        this.xadesSigner = xadesSigner;
        this.audit = audit;
    }

    /**
     * Emite una factura: genera XML, firma, deja en estado FIRMADA listo para envío async.
     *
     * @param request datos de la factura
     * @param ambiente PRUEBAS o PRODUCCION (lo determina el caller según el toggle de la empresa)
     * @return comprobante creado (estado FIRMADA)
     */
    @Transactional
    public Comprobante emitirFactura(EmitirFacturaRequest request, String ambiente) {
        UUID empresaId = TenantContext.getEmpresaId();
        if (empresaId == null) {
            throw new AppException(ErrorCode.NO_AUTORIZADO, "Sin empresa activa en la sesión");
        }
        Empresa empresa = empresaRepo.findById(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA, "Empresa no encontrada"));

        // 1. Establecimiento + punto_emision (pertenencia a la empresa via RLS)
        Establecimiento est = establecimientoRepo.findById(request.establecimientoId())
            .orElseThrow(() -> new AppException(ErrorCode.ESTABLECIMIENTO_NO_ENCONTRADO,
                "Establecimiento no encontrado"));
        PuntoEmision pto = puntoEmisionRepo.findById(request.puntoEmisionId())
            .orElseThrow(() -> new AppException(ErrorCode.PUNTO_EMISION_NO_ENCONTRADO,
                "Punto de emisión no encontrado"));
        if (!pto.getEstablecimientoId().equals(est.getId())) {
            throw new AppException(ErrorCode.PUNTO_EMISION_NO_ENCONTRADO,
                "El punto de emisión no pertenece al establecimiento indicado");
        }

        // 2. Certificado activo
        CertificadoFirma cert = certificadoRepo.findActivo(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.CERTIFICADO_NO_CARGADO,
                "La empresa no tiene un certificado de firma activo. Cargá el .p12 primero."));
        if (cert.getVigenteHasta().isBefore(java.time.Instant.now())) {
            throw new AppException(ErrorCode.CERTIFICADO_CADUCADO,
                "El certificado de firma está caducado");
        }

        // 3. Secuencial FOR UPDATE (gate Sprint 3 ③: 100 paralelas sin saltos)
        Secuencial.Ambiente sriAmbiente = Secuencial.Ambiente.valueOf(ambiente);
        Secuencial sec = secuencialRepo.findForUpdate(
                pto.getId(), Secuencial.TipoComprobante.FACTURA, sriAmbiente)
            .orElseThrow(() -> new AppException(ErrorCode.SECUENCIAL_NO_CONFIGURADO,
                "No hay secuencial FACTURA configurado para este punto de emisión en " + ambiente));
        long numero = sec.asignarYAvanzar();
        secuencialRepo.save(sec);  // explícito por claridad; @Transactional commit lo persiste

        // 4. numero_comprobante + clave_acceso
        String numeroComprobante = "%s-%s-%09d"
            .formatted(est.getCodigo(), pto.getCodigo(), numero);
        LocalDate fechaEmision = request.fechaEmision() != null ? request.fechaEmision() : LocalDate.now();
        String claveAcceso = ClaveAccesoGenerator.generar(
            fechaEmision, ClaveAccesoGenerator.TipoDocumento.FACTURA,
            empresa.getRuc(),
            ClaveAccesoGenerator.Ambiente.valueOf(ambiente),
            est.getCodigo(), pto.getCodigo(), numero);

        // Idempotencia: si ya existe un comprobante con este número en este ambiente, error claro
        if (comprobanteRepo.existsByEmpresaIdAndTipoComprobanteAndAmbienteAndNumeroComprobante(
                empresaId, "FACTURA", ambiente, numeroComprobante)) {
            throw new AppException(ErrorCode.COMPROBANTE_DUPLICADO,
                "Ya existe una factura " + numeroComprobante + " en ambiente " + ambiente);
        }

        // 5. Cabecera
        Comprobante c = Comprobante.nuevo(
            empresaId, est.getId(), pto.getId(), sec.getId(),
            "FACTURA", ambiente, numero, numeroComprobante, claveAcceso, fechaEmision);
        var rec = request.receptor();
        c.receptor(rec.tipoId(), rec.identificacion(), rec.razonSocial(),
                   rec.direccion(), rec.email(), rec.telefono());
        c.formaPago(
            request.formaPago() != null ? request.formaPago() : "01",
            request.plazoDias() != null ? request.plazoDias() : 0);
        c.setCreadoPorUsuarioId(TenantContext.getUsuarioId());

        // 6. Persistir cabecera primero (BORRADOR, totales = 0) para obtener c.id real
        comprobanteRepo.save(c);

        // 7. Construir detalles con el c.id real + calcular totales + actualizar cabecera
        List<ComprobanteDetalle> detalles = construirDetalles(request.items(), empresaId, c.getId());
        Totales totales = sumar(detalles);
        c.totales(totales.subtotalSinImpuestos, totales.totalDescuento,
                  totales.totalIva, totales.importeTotal);
        detalleRepo.saveAll(detalles);

        // 8. XML + XSD
        Document xmlDoc = FacturaXmlBuilder.construir(empresa, c, detalles);
        try {
            xsdValidator.validarFactura(xmlDoc);
        } catch (XsdValidator.XsdValidationException e) {
            throw new AppException(ErrorCode.XML_INVALIDO,
                "El XML generado no cumple el XSD SRI: " + e.getMessage());
        }

        // 9. Firma XAdES-BES (descifra .p12 en memoria, no toca disco)
        byte[] xmlFirmado;
        try {
            CertificadoService.DescifradoEnMemoria desc =
                certificadoService.descifrarParaFirmar(cert.getId());
            xadesSigner.firmar(xmlDoc, desc.p12(), desc.password());
            xmlFirmado = FacturaXmlBuilder.serializar(xmlDoc);
        } catch (XadesSigner.FirmaException e) {
            throw new AppException(ErrorCode.FIRMA_FALLIDA,
                "No se pudo firmar la factura: " + e.getMessage());
        }

        // 10. Estado FIRMADA + evento (incluye trazabilidad del cert que firmó — Fase G)
        c.marcarFirmado(xmlFirmado, cert.getId());
        comprobanteRepo.save(c);
        eventoRepo.save(ComprobanteEvento.nuevo(
            empresaId, c.getId(), Estado.BORRADOR, Estado.FIRMADA, "Factura generada y firmada"));

        audit.log("comprobante_emitido", "comprobante", c.getId(),
            "clave_acceso=" + claveAcceso + " numero=" + numeroComprobante
            + " total=" + totales.importeTotal + " ambiente=" + ambiente);
        log.info("Factura {} firmada — clave_acceso={} empresa={}",
            numeroComprobante, claveAcceso, empresaId);

        // 11. Encolar job de envío async al SRI. JobRunr persiste el request en jobrunr_jobs y
        // arranca el procesamiento en el worker pool (configurado a 2 workers en application.yml).
        BackgroundJobRequest.enqueue(
            new EnviarComprobanteRequest(c.getId(), empresaId, ambiente));

        return c;
    }

    /**
     * Emite una NOTA DE CRÉDITO (codDoc=04). Patrón paralelo a {@link #emitirFactura}:
     * mismo flujo (cert + secuencial NC + claveAcceso + persistencia + XML + firma + job),
     * con un XML distinto (NotaCreditoXmlBuilder) y campos adicionales obligatorios:
     * docModificadoTipo + docModificadoNumero + docModificadoFecha + motivo.
     *
     * NO valida que el doc original exista en BD: una NC puede referenciar un comprobante
     * emitido por OTRO sistema (migración, etc.). Solo valida que los campos vengan completos.
     */
    @Transactional
    public Comprobante emitirNotaCredito(EmisionDtos.EmitirNotaCreditoRequest request, String ambiente) {
        UUID empresaId = TenantContext.getEmpresaId();
        if (empresaId == null) {
            throw new AppException(ErrorCode.NO_AUTORIZADO, "Sin empresa activa en la sesión");
        }
        Empresa empresa = empresaRepo.findById(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA, "Empresa no encontrada"));

        Establecimiento est = establecimientoRepo.findById(request.establecimientoId())
            .orElseThrow(() -> new AppException(ErrorCode.ESTABLECIMIENTO_NO_ENCONTRADO, "Establecimiento no encontrado"));
        PuntoEmision pto = puntoEmisionRepo.findById(request.puntoEmisionId())
            .orElseThrow(() -> new AppException(ErrorCode.PUNTO_EMISION_NO_ENCONTRADO, "Punto de emisión no encontrado"));

        CertificadoFirma cert = certificadoRepo.findActivo(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.CERTIFICADO_NO_CARGADO,
                "La empresa no tiene un certificado de firma activo"));
        if (cert.getVigenteHasta().isBefore(java.time.Instant.now())) {
            throw new AppException(ErrorCode.CERTIFICADO_CADUCADO, "El certificado de firma está caducado");
        }

        Secuencial.Ambiente sriAmbiente = Secuencial.Ambiente.valueOf(ambiente);
        Secuencial sec = secuencialRepo.findForUpdate(pto.getId(), Secuencial.TipoComprobante.NOTA_CREDITO, sriAmbiente)
            .orElseThrow(() -> new AppException(ErrorCode.SECUENCIAL_NO_CONFIGURADO,
                "No hay secuencial NOTA_CREDITO configurado para este punto de emisión en " + ambiente));
        long numero = sec.asignarYAvanzar();
        secuencialRepo.save(sec);

        String numeroComprobante = "%s-%s-%09d".formatted(est.getCodigo(), pto.getCodigo(), numero);
        LocalDate fechaEmision = request.fechaEmision() != null ? request.fechaEmision() : LocalDate.now();
        String claveAcceso = ClaveAccesoGenerator.generar(
            fechaEmision, ClaveAccesoGenerator.TipoDocumento.NOTA_CREDITO,
            empresa.getRuc(), ClaveAccesoGenerator.Ambiente.valueOf(ambiente),
            est.getCodigo(), pto.getCodigo(), numero);

        if (comprobanteRepo.existsByEmpresaIdAndTipoComprobanteAndAmbienteAndNumeroComprobante(
                empresaId, "NOTA_CREDITO", ambiente, numeroComprobante)) {
            throw new AppException(ErrorCode.COMPROBANTE_DUPLICADO,
                "Ya existe una NC " + numeroComprobante + " en ambiente " + ambiente);
        }

        Comprobante c = Comprobante.nuevo(
            empresaId, est.getId(), pto.getId(), sec.getId(),
            "NOTA_CREDITO", ambiente, numero, numeroComprobante, claveAcceso, fechaEmision);
        var rec = request.receptor();
        c.receptor(rec.tipoId(), rec.identificacion(), rec.razonSocial(),
                   rec.direccion(), rec.email(), rec.telefono());
        c.notaCreditoSobre(request.docModificadoTipo(), request.docModificadoNumero(),
                           request.docModificadoFecha(), request.motivo());
        c.formaPago("01", 0);  // NC no se cobra, igual SRI exige forma de pago en algunos XSDs
        c.setCreadoPorUsuarioId(TenantContext.getUsuarioId());
        comprobanteRepo.save(c);

        List<ComprobanteDetalle> detalles = construirDetalles(request.items(), empresaId, c.getId());
        Totales totales = sumar(detalles);
        c.totales(totales.subtotalSinImpuestos, totales.totalDescuento,
                  totales.totalIva, totales.importeTotal);
        detalleRepo.saveAll(detalles);

        Document xmlDoc = NotaCreditoXmlBuilder.construir(empresa, c, detalles);
        // Validación estructural local contra notaCredito_v1.1.0.xsd — atrapa errores
        // (campo faltante, formato mal, codDocModificado inválido) antes de firmar y mandar
        // al SRI. Si el XSD lanza, abortamos con error claro vs descubrirlo por el SRI.
        try {
            xsdValidator.validarNotaCredito(xmlDoc);
        } catch (XsdValidator.XsdValidationException e) {
            throw new AppException(ErrorCode.VALIDACION,
                "El XML de la Nota de Crédito no cumple el esquema SRI: " + e.getMessage());
        }

        byte[] xmlFirmado;
        try {
            CertificadoService.DescifradoEnMemoria desc = certificadoService.descifrarParaFirmar(cert.getId());
            xadesSigner.firmar(xmlDoc, desc.p12(), desc.password());
            xmlFirmado = FacturaXmlBuilder.serializar(xmlDoc);
        } catch (XadesSigner.FirmaException e) {
            throw new AppException(ErrorCode.FIRMA_FALLIDA, "No se pudo firmar la NC: " + e.getMessage());
        }

        c.marcarFirmado(xmlFirmado, cert.getId());
        comprobanteRepo.save(c);
        eventoRepo.save(ComprobanteEvento.nuevo(
            c.getEmpresaId(), c.getId(), Comprobante.Estado.BORRADOR, Comprobante.Estado.FIRMADA,
            "NC generada y firmada — modifica " + request.docModificadoTipo() + " " + request.docModificadoNumero()));

        audit.log("nc_emitida", "comprobante", c.getId(),
            "clave_acceso=" + claveAcceso + " numero=" + numeroComprobante
            + " modifica=" + request.docModificadoNumero() + " ambiente=" + ambiente);
        log.info("NC {} firmada — clave_acceso={} empresa={}",
            numeroComprobante, claveAcceso, empresaId);

        BackgroundJobRequest.enqueue(
            new EnviarComprobanteRequest(c.getId(), empresaId, ambiente));

        return c;
    }

    /**
     * Cancela un comprobante que aún no fue autorizado por el SRI. Transiciona a ABANDONADA
     * (estado terminal) y registra el evento.
     *
     * Estados permitidos: BORRADOR, FIRMADA, DEVUELTA. Otros casos:
     *   - ENVIADA / EN_PROCESO: el comprobante está en tránsito al SRI; hay que esperar.
     *     El SRI puede aún autorizarlo o devolverlo. Después se puede cancelar/anular.
     *   - AUTORIZADA: requiere ANULACIÓN al SRI (Sprint 5, HU-F6) — no se puede borrar local.
     *   - NO_AUTORIZADA / ABANDONADA: ya terminales.
     */
    @Transactional
    public Comprobante cancelar(UUID comprobanteId, String motivo) {
        UUID empresaId = TenantContext.getEmpresaId();
        if (empresaId == null) {
            throw new AppException(ErrorCode.NO_AUTORIZADO, "Sin empresa activa en la sesión");
        }
        Comprobante c = comprobanteRepo.findById(comprobanteId)
            .orElseThrow(() -> new AppException(ErrorCode.COMPROBANTE_NO_ENCONTRADO,
                "Comprobante no encontrado"));

        Estado anterior = c.getEstado();
        switch (anterior) {
            case BORRADOR, FIRMADA, DEVUELTA -> { /* OK, sigue abajo */ }
            case ENVIADA, EN_PROCESO -> throw new AppException(ErrorCode.VALIDACION,
                "Esta factura está en proceso de envío al SRI. Esperá la respuesta antes de cancelar.");
            case AUTORIZADA -> throw new AppException(ErrorCode.VALIDACION,
                "Esta factura ya fue autorizada por el SRI. Para anularla hacé el trámite en el portal SRI (anulación electrónica).");
            case NO_AUTORIZADA, ABANDONADA -> throw new AppException(ErrorCode.VALIDACION,
                "Esta factura ya está en estado terminal (" + anterior + "), no se puede cancelar.");
        }

        c.cambiarEstado(Estado.ABANDONADA);
        comprobanteRepo.save(c);

        String mensaje = "Cancelada por el usuario";
        if (motivo != null && !motivo.isBlank()) {
            mensaje += ": " + motivo;
        }
        eventoRepo.save(ComprobanteEvento.nuevo(
            empresaId, c.getId(), anterior, Estado.ABANDONADA, mensaje));

        audit.log("comprobante_cancelado", "comprobante", c.getId(),
            "clave_acceso=" + c.getClaveAcceso() + " estado_anterior=" + anterior);
        log.info("Comprobante {} cancelado por usuario (estado anterior: {})",
            c.getClaveAcceso(), anterior);
        return c;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers cálculo
    // ─────────────────────────────────────────────────────────────────────

    private List<ComprobanteDetalle> construirDetalles(List<Item> items, UUID empresaId, UUID comprobanteId) {
        List<ComprobanteDetalle> out = new ArrayList<>(items.size());
        int orden = 1;
        for (Item item : items) {
            out.add(detalleDesdeItem(item, empresaId, comprobanteId, orden++));
        }
        return out;
    }

    private ComprobanteDetalle detalleDesdeItem(Item item, UUID empresaId, UUID comprobanteId, int orden) {
        BigDecimal descuento = item.descuento() != null ? item.descuento() : BigDecimal.ZERO;
        BigDecimal precioTotalSinImpuesto = item.cantidad()
            .multiply(item.precioUnitario())
            .subtract(descuento)
            .setScale(2, RoundingMode.HALF_UP);
        if (precioTotalSinImpuesto.signum() < 0) {
            throw new AppException(ErrorCode.VALIDACION,
                "El descuento del ítem '" + item.descripcion() + "' supera el subtotal");
        }
        CodigoPorcentajeIva iva = CodigoPorcentajeIva.valueOf(item.codigoIva());
        BigDecimal base = precioTotalSinImpuesto;
        BigDecimal valorImpuesto = base
            .multiply(iva.tarifa)
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        ComprobanteDetalle d = ComprobanteDetalle.nuevo(
            empresaId, comprobanteId, orden,
            item.codigoPrincipal(), item.descripcion(),
            item.cantidad(), item.precioUnitario(), descuento, precioTotalSinImpuesto,
            iva, base, valorImpuesto);
        if (item.codigoAuxiliar() != null && !item.codigoAuxiliar().isBlank()) {
            d.setCodigoAuxiliar(item.codigoAuxiliar());
        }
        return d;
    }

    private Totales sumar(List<ComprobanteDetalle> detalles) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal descuento = BigDecimal.ZERO;
        BigDecimal iva = BigDecimal.ZERO;
        for (ComprobanteDetalle d : detalles) {
            subtotal = subtotal.add(d.getPrecioTotalSinImpuesto());
            descuento = descuento.add(d.getDescuento());
            iva = iva.add(d.getValorImpuesto());
        }
        BigDecimal importeTotal = subtotal.add(iva).setScale(2, RoundingMode.HALF_UP);
        return new Totales(subtotal.setScale(2, RoundingMode.HALF_UP),
                            descuento.setScale(2, RoundingMode.HALF_UP),
                            iva.setScale(2, RoundingMode.HALF_UP),
                            importeTotal);
    }

    private record Totales(BigDecimal subtotalSinImpuestos, BigDecimal totalDescuento,
                            BigDecimal totalIva, BigDecimal importeTotal) {}
}
