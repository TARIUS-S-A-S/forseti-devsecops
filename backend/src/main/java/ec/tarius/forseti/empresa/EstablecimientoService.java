package ec.tarius.forseti.empresa;

import ec.tarius.forseti.empresa.domain.Establecimiento;
import ec.tarius.forseti.empresa.domain.PuntoEmision;
import ec.tarius.forseti.empresa.domain.Secuencial;
import ec.tarius.forseti.empresa.dto.EmpresaDtos.*;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class EstablecimientoService {

    private final EstablecimientoRepository establecimientoRepo;
    private final PuntoEmisionRepository puntoEmisionRepo;
    private final SecuencialRepository secuencialRepo;
    private final AuditLogger audit;

    public EstablecimientoService(EstablecimientoRepository establecimientoRepo,
                                   PuntoEmisionRepository puntoEmisionRepo,
                                   SecuencialRepository secuencialRepo,
                                   AuditLogger audit) {
        this.establecimientoRepo = establecimientoRepo;
        this.puntoEmisionRepo = puntoEmisionRepo;
        this.secuencialRepo = secuencialRepo;
        this.audit = audit;
    }

    @Transactional
    public Establecimiento crear(UUID empresaId, CrearEstablecimientoRequest req) {
        if (establecimientoRepo.existsByEmpresaIdAndCodigo(empresaId, req.codigo())) {
            throw new AppException(ErrorCode.ESTABLECIMIENTO_DUPLICADO,
                "Ya existe un establecimiento con código " + req.codigo());
        }
        Establecimiento e = Establecimiento.nuevo(empresaId, req.codigo(), req.nombre(), req.direccion());
        establecimientoRepo.save(e);
        audit.log("establecimiento_creado", "establecimiento", e.getId(),
            "empresa_id=" + empresaId + " codigo=" + req.codigo());
        return e;
    }

    @Transactional(readOnly = true)
    public List<Establecimiento> listar(UUID empresaId) {
        return establecimientoRepo.findByEmpresaIdOrderByCodigoAsc(empresaId);
    }

    @Transactional
    public PuntoEmision crearPunto(UUID empresaId, UUID establecimientoId, CrearPuntoEmisionRequest req) {
        Establecimiento est = establecimientoRepo.findById(establecimientoId)
            .orElseThrow(() -> new AppException(ErrorCode.ESTABLECIMIENTO_NO_ENCONTRADO,
                "Establecimiento no encontrado"));
        if (!est.getEmpresaId().equals(empresaId)) {
            throw new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "El establecimiento no pertenece a la empresa activa");
        }
        if (puntoEmisionRepo.existsByEstablecimientoIdAndCodigo(establecimientoId, req.codigo())) {
            throw new AppException(ErrorCode.PUNTO_EMISION_DUPLICADO,
                "Ya existe un punto de emisión con código " + req.codigo() + " en este establecimiento");
        }
        PuntoEmision p = PuntoEmision.nuevo(empresaId, establecimientoId, req.codigo(), req.descripcion());
        puntoEmisionRepo.save(p);

        // Inicializa secuenciales para todos los tipos de comprobante en ambiente PRUEBAS
        for (Secuencial.TipoComprobante tipo : Secuencial.TipoComprobante.values()) {
            secuencialRepo.save(Secuencial.nuevo(empresaId, p.getId(), tipo,
                Secuencial.Ambiente.PRUEBAS, 1L));
        }

        audit.log("punto_emision_creado", "punto_emision", p.getId(),
            "empresa_id=" + empresaId + " establecimiento_id=" + establecimientoId
            + " codigo=" + req.codigo());
        return p;
    }

    @Transactional(readOnly = true)
    public List<PuntoEmision> listarPuntos(UUID establecimientoId) {
        return puntoEmisionRepo.findByEstablecimientoIdOrderByCodigoAsc(establecimientoId);
    }

    /**
     * Configura el secuencial de un (puntoEmision, tipo, ambiente). Si ya existe, actualiza
     * proximoNumero a {@code ultimoNumeroEmitido + 1}; si no existe, lo crea.
     *
     * Use case principal: empresas que migran a Forseti desde otro sistema y ya tienen
     * facturas emitidas. Tipean el último número que SRI registró (por ejemplo 247) y
     * Forseti arranca desde 248.
     *
     * Validación: solo permite "saltar" hacia adelante (nunca atrás). Si ya emitiste
     * con Forseti hasta el 100 y querés bajar a 50, el SRI rechazaría con secuencial
     * duplicado. Por seguridad lo bloqueamos en código.
     */
    @Transactional
    public Secuencial configurarSecuencial(UUID empresaId, UUID puntoEmisionId,
                                            Secuencial.TipoComprobante tipo,
                                            Secuencial.Ambiente ambiente,
                                            long ultimoNumeroEmitido) {
        if (ultimoNumeroEmitido < 0) {
            throw new AppException(ErrorCode.VALIDACION,
                "El último número emitido no puede ser negativo");
        }
        // Pertenencia: el punto de emisión debe ser de la empresa activa.
        PuntoEmision pe = puntoEmisionRepo.findById(puntoEmisionId)
            .orElseThrow(() -> new AppException(ErrorCode.PUNTO_EMISION_NO_ENCONTRADO,
                "Punto de emisión no encontrado"));
        if (!pe.getEmpresaId().equals(empresaId)) {
            throw new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "El punto de emisión no pertenece a la empresa activa");
        }

        Secuencial existente = secuencialRepo
            .findForUpdate(puntoEmisionId, tipo, ambiente).orElse(null);

        long proximo = ultimoNumeroEmitido + 1;
        if (existente != null) {
            if (existente.getProximoNumero() > proximo) {
                throw new AppException(ErrorCode.VALIDACION,
                    "El secuencial actual ya está en " + existente.getProximoNumero()
                    + " — no se puede retroceder a " + proximo
                    + " (el SRI rechazaría por número duplicado).");
            }
            existente.setProximoNumero(proximo);
            secuencialRepo.save(existente);
            audit.log("secuencial_actualizado", "secuencial", existente.getId(),
                "empresa_id=" + empresaId + " tipo=" + tipo + " ambiente=" + ambiente
                + " proximo=" + proximo);
            return existente;
        }
        Secuencial nuevo = Secuencial.nuevo(empresaId, puntoEmisionId, tipo, ambiente, proximo);
        secuencialRepo.save(nuevo);
        audit.log("secuencial_creado", "secuencial", nuevo.getId(),
            "empresa_id=" + empresaId + " tipo=" + tipo + " ambiente=" + ambiente
            + " proximo=" + proximo);
        return nuevo;
    }

    @Transactional(readOnly = true)
    public List<Secuencial> listarSecuenciales(UUID puntoEmisionId) {
        return secuencialRepo.findByPuntoEmisionIdOrderByAmbienteAscTipoComprobanteAsc(puntoEmisionId);
    }
}
