package ec.tarius.forseti.empresa;

import ec.tarius.forseti.auth.SesionRepository;
import ec.tarius.forseti.auth.UsuarioEmpresaRepository;
import ec.tarius.forseti.auth.domain.UsuarioEmpresa;
import ec.tarius.forseti.empresa.domain.Empresa;
import ec.tarius.forseti.empresa.domain.PerfilTributario;
import ec.tarius.forseti.empresa.dto.EmpresaDtos.*;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import ec.tarius.forseti.shared.tenant.TenantContext;
import ec.tarius.forseti.shared.validacion.RucValidator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio central de gestión de empresas (HU-F16).
 *
 * Reglas:
 *   - Alta de empresa NO es tenant-aware (la empresa todavía no existe). Se hace fuera de RLS.
 *   - Tras crear, automáticamente se asigna al usuario actual como DUENO y se crea su perfil_tributario
 *     vigente (vigente_desde = hoy, vigente_hasta = NULL).
 *   - Actualizar el perfil tributario NO pisa: cierra la vigencia actual y crea una nueva (historial).
 */
@Service
public class EmpresaService {

    private static final Logger log = LoggerFactory.getLogger(EmpresaService.class);

    private final EmpresaRepository empresaRepo;
    private final PerfilTributarioRepository perfilRepo;
    private final UsuarioEmpresaRepository usuarioEmpresaRepo;
    private final SesionRepository sesionRepo;
    private final CertificadoFirmaRepository certificadoRepo;
    private final SecuencialRepository secuencialRepo;
    private final AuditLogger audit;

    @PersistenceContext
    private EntityManager em;

    public EmpresaService(EmpresaRepository empresaRepo,
                          PerfilTributarioRepository perfilRepo,
                          UsuarioEmpresaRepository usuarioEmpresaRepo,
                          SesionRepository sesionRepo,
                          CertificadoFirmaRepository certificadoRepo,
                          SecuencialRepository secuencialRepo,
                          AuditLogger audit) {
        this.empresaRepo = empresaRepo;
        this.perfilRepo = perfilRepo;
        this.usuarioEmpresaRepo = usuarioEmpresaRepo;
        this.sesionRepo = sesionRepo;
        this.certificadoRepo = certificadoRepo;
        this.secuencialRepo = secuencialRepo;
        this.audit = audit;
    }

    /**
     * Crea una empresa y asocia al usuario actual como DUENO.
     * Crea el perfil_tributario inicial con vigencia abierta.
     */
    @Transactional
    public Empresa crear(CrearEmpresaRequest req, UUID usuarioId) {
        // El aspect TenantTransactionAdvice tiene un problema de orden con @Transactional
        // (corre AFUERA, no DENTRO de la TX), así que el SET LOCAL del aspect no aplica al INSERT.
        // Workaround: setear explícitamente el contexto al inicio del método transactional.
        // Sin esto, el INSERT empresa falla con "new row violates RLS policy" porque la policy
        // WITH CHECK valida current_usuario_id() IS NOT NULL.
        em.createNativeQuery("SELECT set_config('app.usuario_id', :v, true)")
            .setParameter("v", usuarioId.toString())
            .getSingleResult();

        // Validación laxa: formato OK (13 dígitos, provincia, terminado en 001).
        // El dígito verificador NO es bloqueante: hay RUCs reales que el SRI emitió y nuestro
        // algoritmo público los rechaza. El SRI rechazará al emitir comprobantes si está mal de verdad.
        if (!RucValidator.formatoValido(req.ruc())) {
            throw new AppException(ErrorCode.RUC_INVALIDO,
                "RUC inválido: debe tener 13 dígitos, terminar en 001 y empezar con provincia válida (01-24).");
        }
        if (!RucValidator.digitoVerificadorValido(req.ruc())) {
            log.warn("RUC {} pasa formato pero no el dígito verificador del algoritmo SRI público. " +
                "Permitido (puede ser legacy o algoritmo distinto). usuario_id={}", req.ruc(), usuarioId);
        }
        // Nota: existsByRuc() respeta RLS — si el RUC está tomado por otro usuario, este check
        // devuelve false (no es miembro). El INSERT abajo dispara entonces el unique constraint.
        // Capturamos esa excepción en empresaRepo.save y mapeamos al mismo error claro.
        if (empresaRepo.existsByRuc(req.ruc())) {
            throw new AppException(ErrorCode.EMPRESA_YA_REGISTRADA,
                "Ya existe una empresa con ese RUC en Forseti");
        }

        Empresa e = Empresa.nueva(req.ruc(), req.razonSocial().trim());
        e.setNombreComercial(opcional(req.nombreComercial()));
        e.setTipoContribuyente(req.tipoContribuyente());
        e.setRegimenTributario(req.regimenTributario());
        e.setPeriodicidadIva(req.periodicidadIva());
        e.setObligadoContabilidad(req.obligadoContabilidad());
        e.setAgenteRetencion(req.agenteRetencion());
        e.setDireccion(opcional(req.direccion()));
        e.setCiudad(opcional(req.ciudad()));
        e.setProvincia(opcional(req.provincia()));
        e.setTelefono(opcional(req.telefono()));
        e.setEmail(opcional(req.email()));
        try {
            empresaRepo.saveAndFlush(e);
        } catch (org.springframework.dao.DataIntegrityViolationException dex) {
            // Unique constraint del RUC — el existsByRuc() previo no lo vio por RLS
            // (la empresa pertenece a otro usuario).
            if (dex.getMessage() != null && dex.getMessage().contains("empresa_ruc_key")) {
                throw new AppException(ErrorCode.EMPRESA_YA_REGISTRADA,
                    "Ya existe una empresa con ese RUC en Forseti (es de otra cuenta).");
            }
            throw dex;
        }

        // RLS: la empresa recién creada ahora es el tenant activo para esta TX.
        // Sin este SET LOCAL, los INSERT siguientes a tablas tenant-aware (perfil_tributario,
        // establecimiento, etc.) fallarían porque current_empresa_id() no coincide.
        em.createNativeQuery("SELECT set_config('app.empresa_id', :v, true)")
            .setParameter("v", e.getId().toString())
            .getSingleResult();

        // Asignar al usuario actual como DUENO
        UsuarioEmpresa ue = UsuarioEmpresa.nueva(usuarioId, e.getId(), UsuarioEmpresa.Rol.DUENO);
        usuarioEmpresaRepo.save(ue);

        // Crear perfil tributario inicial con vigencia abierta
        PerfilTributario perfil = PerfilTributario.nuevo(
            e.getId(),
            LocalDate.now(ZoneOffset.UTC),
            req.regimenTributario(),
            req.periodicidadIva(),
            req.obligadoContabilidad(),
            req.agenteRetencion(),
            usuarioId
        );
        perfilRepo.save(perfil);

        // Marca la nueva empresa como activa en TODAS las sesiones vigentes del usuario.
        // Sin esto, el próximo GET a un recurso tenant-aware (perfil_tributario, establecimientos…)
        // falla por RLS porque current_empresa_id() seguiría siendo NULL (la sesión del user
        // recién registrado no tenía empresa activa todavía).
        sesionRepo.actualizarEmpresaActivaDelUsuario(usuarioId, e.getId());

        audit.log("empresa_creada", "empresa", e.getId(),
            "ruc=" + req.ruc() + " razon_social=" + req.razonSocial());
        log.info("Empresa creada: ruc={} id={} por usuario_id={}", req.ruc(), e.getId(), usuarioId);
        return e;
    }

    /** Lista las empresas a las que tiene acceso el usuario actual. */
    @Transactional(readOnly = true)
    public List<Empresa> listarMias(UUID usuarioId) {
        var memberships = usuarioEmpresaRepo.findByUsuarioId(usuarioId);
        return memberships.stream()
            .map(ue -> empresaRepo.findById(ue.getEmpresaId()))
            .filter(Optional::isPresent).map(Optional::get)
            .toList();
    }

    /**
     * Cambia la empresa activa de la sesión actual. El usuario debe ser miembro.
     * El próximo request leerá la cookie y verá la nueva empresa activa.
     */
    @Transactional
    public void seleccionarEmpresaActiva(UUID usuarioId, UUID empresaId) {
        boolean miembro = usuarioEmpresaRepo.findByUsuarioId(usuarioId).stream()
            .anyMatch(ue -> ue.getEmpresaId().equals(empresaId));
        if (!miembro) {
            throw new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "No sos miembro de esa empresa");
        }
        int actualizadas = sesionRepo.actualizarEmpresaActivaDelUsuario(usuarioId, empresaId);
        audit.log("empresa_activa_cambiada", "empresa", empresaId,
            "usuario_id=" + usuarioId + " sesiones_actualizadas=" + actualizadas);
    }

    /** Actualiza el perfil tributario: cierra la vigencia anterior y crea una nueva. */
    @Transactional
    public PerfilTributario actualizarPerfilTributario(UUID empresaId,
                                                        ActualizarPerfilTributarioRequest req,
                                                        UUID usuarioId) {
        Empresa e = empresaRepo.findById(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA, "Empresa no encontrada"));

        LocalDate desde = req.vigenteDesde() != null ? req.vigenteDesde() : LocalDate.now(ZoneOffset.UTC);

        // Cerrar la vigencia anterior (si existe)
        perfilRepo.findVigenteActual(empresaId).ifPresent(prev -> {
            if (!desde.isAfter(prev.getVigenteDesde())) {
                throw new AppException(ErrorCode.VALIDACION,
                    "vigenteDesde debe ser posterior a la vigencia actual (" + prev.getVigenteDesde() + ")");
            }
            prev.cerrarVigencia(desde, req.motivoCambio());
            perfilRepo.save(prev);
        });

        PerfilTributario nuevo = PerfilTributario.nuevo(
            empresaId, desde,
            req.regimenTributario(), req.periodicidadIva(),
            req.obligadoContabilidad(), req.agenteRetencion(),
            usuarioId);
        perfilRepo.save(nuevo);

        // Actualizar snapshot en empresa (vista actual)
        e.setRegimenTributario(req.regimenTributario());
        e.setPeriodicidadIva(req.periodicidadIva());
        e.setObligadoContabilidad(req.obligadoContabilidad());
        e.setAgenteRetencion(req.agenteRetencion());
        empresaRepo.save(e);

        audit.log("perfil_tributario_actualizado", "perfil_tributario", nuevo.getId(),
            "empresa_id=" + empresaId + " regimen=" + req.regimenTributario()
            + " periodicidad=" + req.periodicidadIva());
        return nuevo;
    }

    @Transactional(readOnly = true)
    public PerfilTributario perfilVigente(UUID empresaId) {
        return perfilRepo.findVigenteActual(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.PERFIL_NO_ENCONTRADO,
                "La empresa no tiene perfil tributario vigente"));
    }

    @Transactional(readOnly = true)
    public List<PerfilTributario> historialPerfil(UUID empresaId) {
        return perfilRepo.findHistorial(empresaId);
    }

    /**
     * Cambia el ambiente SRI por defecto de la empresa.
     *
     * Pasar a PRODUCCION es una decisión CRÍTICA (las facturas que emita serán fiscales
     * reales). Se valida:
     *   1) la empresa tiene cert activo no caducado,
     *   2) tiene perfil tributario vigente,
     *   3) tiene al menos UN secuencial configurado en PRODUCCION (sino al emitir
     *      fallaría con SECUENCIAL_NO_CONFIGURADO).
     *
     * Pasar a PRUEBAS (rollback) NO requiere validaciones — siempre se puede volver al
     * sandbox para testing.
     */
    @Transactional
    public Empresa cambiarAmbiente(UUID empresaId, String nuevoAmbiente, UUID usuarioId) {
        if (!"PRUEBAS".equals(nuevoAmbiente) && !"PRODUCCION".equals(nuevoAmbiente)) {
            throw new AppException(ErrorCode.VALIDACION,
                "Ambiente debe ser PRUEBAS o PRODUCCION");
        }
        Empresa e = empresaRepo.findById(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA,
                "Empresa no encontrada"));

        if ("PRODUCCION".equals(nuevoAmbiente)) {
            // 1) cert activo no caducado
            var certActivo = certificadoRepo.findActivo(empresaId);
            if (certActivo.isEmpty()) {
                throw new AppException(ErrorCode.CERTIFICADO_NO_CARGADO,
                    "Para pasar a PRODUCCIÓN la empresa necesita un certificado de firma activo.");
            }
            if (certActivo.get().getVigenteHasta().isBefore(java.time.Instant.now())) {
                throw new AppException(ErrorCode.CERTIFICADO_CADUCADO,
                    "El certificado de firma está caducado — renovalo antes de pasar a PRODUCCIÓN.");
            }
            // 2) perfil tributario vigente
            perfilRepo.findVigenteActual(empresaId)
                .orElseThrow(() -> new AppException(ErrorCode.PERFIL_NO_ENCONTRADO,
                    "Para pasar a PRODUCCIÓN la empresa necesita un perfil tributario vigente."));
            // 3) al menos 1 secuencial PRODUCCION configurado
            long secPro = secuencialRepo.contarPorEmpresaYAmbiente(empresaId,
                ec.tarius.forseti.empresa.domain.Secuencial.Ambiente.PRODUCCION);
            if (secPro == 0) {
                throw new AppException(ErrorCode.SECUENCIAL_NO_CONFIGURADO,
                    "Para pasar a PRODUCCIÓN la empresa necesita al menos un punto de "
                    + "emisión con secuencial PRODUCCION configurado. Andá a "
                    + "Configuración → Establecimientos y creá los secuenciales necesarios.");
            }
        }

        String anterior = e.getAmbienteDefault();
        e.setAmbienteDefault(nuevoAmbiente);
        empresaRepo.save(e);
        audit.log("empresa_ambiente_cambiado", "empresa", empresaId,
            "anterior=" + anterior + " nuevo=" + nuevoAmbiente + " usuario_id=" + usuarioId);
        log.info("Empresa {} cambió ambiente {} → {} (usuario {})",
            empresaId, anterior, nuevoAmbiente, usuarioId);
        return e;
    }

    /**
     * Archiva una empresa (soft-delete). Marca {@code activa = false} en lugar de borrar
     * físicamente: preserva trazabilidad legal (facturas, NCs, eventos) y permite
     * reactivar si fue por error.
     *
     * Reglas:
     *   1. Solo el DUEÑO puede archivar (ADMIN no — es decisión societaria).
     *   2. Si la empresa estaba activa en la sesión del usuario, se le quita la activa
     *      (queda sin empresa activa hasta que elija otra).
     *   3. Si la empresa tiene comprobantes AUTORIZADOS, NO se puede borrar físicamente;
     *      solo archivar. Bloqueamos el delete físico (no exponemos endpoint para eso).
     */
    @Transactional
    public Empresa archivar(UUID empresaId, UUID usuarioId) {
        Empresa e = empresaRepo.findById(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA, "Empresa no encontrada"));

        var rol = usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(usuarioId, empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "No tenés acceso a esta empresa"));
        if (rol.getRol() != ec.tarius.forseti.auth.domain.UsuarioEmpresa.Rol.DUENO) {
            throw new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "Solo el DUEÑO de la empresa puede archivarla");
        }
        if (!e.isActiva()) {
            // ya archivada — idempotente
            return e;
        }
        e.setActiva(false);
        empresaRepo.save(e);

        // Limpiar de las sesiones activas: si algún usuario tenía esta empresa como activa,
        // queda sin selección. Lo manejamos vía la sesión actual (sólo del usuario que archiva).
        sesionRepo.limpiarEmpresaActivaDelUsuarioSiEs(usuarioId, empresaId);

        audit.log("empresa_archivada", "empresa", empresaId,
            "usuario_id=" + usuarioId + " razon_social=" + e.getRazonSocial());
        log.info("Empresa {} archivada por usuario {}", e.getRazonSocial(), usuarioId);
        return e;
    }

    /**
     * Reactiva una empresa archivada. Las facturas/historial siguen intactas.
     */
    @Transactional
    public Empresa reactivar(UUID empresaId, UUID usuarioId) {
        Empresa e = empresaRepo.findById(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA, "Empresa no encontrada"));
        var rol = usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(usuarioId, empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.SIN_ACCESO_EMPRESA, "No tenés acceso a esta empresa"));
        if (rol.getRol() != ec.tarius.forseti.auth.domain.UsuarioEmpresa.Rol.DUENO) {
            throw new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "Solo el DUEÑO puede reactivar la empresa");
        }
        if (e.isActiva()) return e;
        e.setActiva(true);
        empresaRepo.save(e);
        audit.log("empresa_reactivada", "empresa", empresaId, "usuario_id=" + usuarioId);
        return e;
    }

    private static String opcional(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
