package ec.tarius.forseti.compras.service;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.compras.domain.IngresoManual;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearIngresoManualRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.IngresoManualResponse;
import ec.tarius.forseti.compras.repo.IngresoManualRepository;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ingresos manuales (HU-F1) — para ventas previas a Forseti o ventas que no se
 * emiten vía Forseti. El flujo normal de ventas es HU-F7 (emisión SRI).
 * Anulación HU-F6: soft-delete con flag + motivo, NUNCA borrar (RNF-6).
 */
@Service
public class IngresoManualService {

    private static final Logger log = LoggerFactory.getLogger(IngresoManualService.class);

    private final IngresoManualRepository repo;

    public IngresoManualService(IngresoManualRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public IngresoManual crear(CrearIngresoManualRequest req) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        UUID usuarioId = UsuarioActual.idObligatorio();

        IngresoManual i = IngresoManual.nuevo(empresaId, req.fechaEmision(),
            req.clienteRazonSocial(), req.concepto(),
            nvlZero(req.total()));
        i.setClienteIdentificacion(req.clienteIdentificacion());
        i.bases(nvlZero(req.baseIva15()), nvlZero(req.baseIva0()),
                nvlZero(req.valorIva15()), nvlZero(req.retencionRecibida()));
        if (req.fechaCobro() != null) {
            i.marcarCobrado(req.fechaCobro());
        }
        i.setCreadoPorUsuarioId(usuarioId);

        IngresoManual guardado = repo.save(i);
        log.info("Ingreso MANUAL creado: id={} empresa={} cliente={} total={}",
            guardado.getId(), empresaId, req.clienteRazonSocial(), req.total());
        return guardado;
    }

    @Transactional(readOnly = true)
    public IngresoManual obtener(UUID id) {
        UsuarioActual.empresaActivaObligatoria();
        return repo.findById(id)
            .orElseThrow(() -> new AppException(ErrorCode.INGRESO_NO_ENCONTRADO,
                "Ingreso no encontrado: " + id));
    }

    @Transactional(readOnly = true)
    public List<IngresoManual> listar(LocalDate desde, LocalDate hasta) {
        UUID empresaId = UsuarioActual.empresaActivaObligatoria();
        LocalDate d = desde != null ? desde : LocalDate.now().withDayOfMonth(1);
        LocalDate h = hasta != null ? hasta : LocalDate.now();
        return repo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            empresaId, d, h);
    }

    @Transactional
    public IngresoManual anular(UUID id, String motivo) {
        UUID usuarioId = UsuarioActual.idObligatorio();
        IngresoManual i = obtener(id);
        if (i.isAnulada()) {
            throw new AppException(ErrorCode.INGRESO_YA_ANULADO,
                "Este ingreso ya está anulado (motivo previo: " + i.getMotivoAnulacion() + ")");
        }
        i.anular(usuarioId, motivo);
        log.info("Ingreso manual ANULADO: id={} por={} motivo='{}'", id, usuarioId, motivo);
        return i;
    }

    @Transactional
    public IngresoManual marcarCobrado(UUID id, LocalDate fechaCobro) {
        if (fechaCobro == null) {
            throw new AppException(ErrorCode.VALIDACION, "fechaCobro es obligatoria");
        }
        IngresoManual i = obtener(id);
        if (i.isAnulada()) {
            throw new AppException(ErrorCode.INGRESO_YA_ANULADO,
                "No se puede cobrar un ingreso anulado");
        }
        // fechaCobro debe ser >= fechaEmision y <= hoy
        LocalDate hoy = LocalDate.now();
        if (fechaCobro.isAfter(hoy)) {
            throw new AppException(ErrorCode.VALIDACION,
                "fechaCobro no puede ser futura (recibida " + fechaCobro + ", hoy " + hoy + ")");
        }
        if (i.getFechaEmision() != null && fechaCobro.isBefore(i.getFechaEmision())) {
            throw new AppException(ErrorCode.VALIDACION,
                "fechaCobro no puede ser anterior a fechaEmision (cobro " + fechaCobro
                + ", emisión " + i.getFechaEmision() + ")");
        }
        i.marcarCobrado(fechaCobro);
        return i;
    }

    public IngresoManualResponse toResponse(IngresoManual i) {
        return new IngresoManualResponse(
            i.getId(), i.getFechaEmision(),
            i.getClienteIdentificacion(), i.getClienteRazonSocial(), i.getConcepto(),
            i.getBaseIva15(), i.getBaseIva0(), i.getValorIva15(), i.getRetencionRecibida(),
            i.getTotal(), i.getEstadoCobro().name(), i.getFechaCobro(),
            i.isAnulada(), i.getAnuladaAt(), i.getMotivoAnulacion(),
            i.getCreadoAt());
    }

    private static BigDecimal nvlZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
