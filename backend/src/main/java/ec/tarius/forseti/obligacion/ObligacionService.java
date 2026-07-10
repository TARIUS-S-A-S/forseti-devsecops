package ec.tarius.forseti.obligacion;

import ec.tarius.forseti.empresa.PerfilTributarioRepository;
import ec.tarius.forseti.empresa.domain.PerfilTributario;
import ec.tarius.forseti.obligacion.domain.ObligacionCatalogo;
import ec.tarius.forseti.obligacion.domain.ObligacionEmpresa;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ObligacionService {

    private final ObligacionCatalogoRepository catalogoRepo;
    private final ObligacionEmpresaRepository empresaRepo;
    private final PerfilTributarioRepository perfilRepo;
    private final AuditLogger audit;

    public ObligacionService(ObligacionCatalogoRepository catalogoRepo,
                              ObligacionEmpresaRepository empresaRepo,
                              PerfilTributarioRepository perfilRepo,
                              AuditLogger audit) {
        this.catalogoRepo = catalogoRepo;
        this.empresaRepo = empresaRepo;
        this.perfilRepo = perfilRepo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ObligacionCatalogo> catalogo() {
        return catalogoRepo.findByActivaTrueOrderByOrdenAsc();
    }

    @Transactional(readOnly = true)
    public List<ObligacionEmpresa> activadasPorEmpresa(UUID empresaId) {
        return empresaRepo.findByEmpresaId(empresaId);
    }

    /**
     * Pre-selecciona las obligaciones que aplican al perfil actual de la empresa.
     * No las activa todavía — devuelve la lista para que el usuario revise y confirme.
     */
    @Transactional(readOnly = true)
    public List<ObligacionCatalogo> sugeridas(UUID empresaId) {
        Optional<PerfilTributario> perfilOpt = perfilRepo.findVigenteActual(empresaId);
        if (perfilOpt.isEmpty()) return List.of();
        PerfilTributario perfil = perfilOpt.get();
        return catalogo().stream()
            .filter(o -> aplica(o, perfil))
            .toList();
    }

    /** Evalúa la condición aplica_si del catálogo contra el perfil vigente. */
    private boolean aplica(ObligacionCatalogo o, PerfilTributario perfil) {
        var aplica = o.getAplicaSi();
        if (aplica == null || aplica.isEmpty()) return true;

        if (aplica.containsKey("regimen")) {
            List<?> permitidos = (List<?>) aplica.get("regimen");
            if (!permitidos.contains(perfil.getRegimenTributario())) return false;
        }
        if (aplica.containsKey("periodicidad_iva")) {
            List<?> permitidos = (List<?>) aplica.get("periodicidad_iva");
            if (!permitidos.contains(perfil.getPeriodicidadIva())) return false;
        }
        if (aplica.containsKey("agente_retencion")) {
            List<?> permitidos = (List<?>) aplica.get("agente_retencion");
            if (!permitidos.contains(perfil.isAgenteRetencion())) return false;
        }
        // tipo_contribuyente, tiene_empleados, tiene_local, municipio se evalúan
        // contra empresa (no contra perfil). Sprint 2: sugerencia mínima.
        return true;
    }

    @Transactional
    public ObligacionEmpresa activar(UUID empresaId, String codigo, UUID usuarioId) {
        catalogoRepo.findById(codigo)
            .orElseThrow(() -> new AppException(ErrorCode.OBLIGACION_NO_ENCONTRADA,
                "Obligación no existe en catálogo: " + codigo));

        Optional<ObligacionEmpresa> existente = empresaRepo.findByEmpresaIdAndObligacionCodigo(empresaId, codigo);
        if (existente.isPresent()) {
            ObligacionEmpresa e = existente.get();
            e.activar();
            empresaRepo.save(e);
            audit.log("obligacion_activada", "obligacion_empresa", e.getId(),
                "empresa_id=" + empresaId + " codigo=" + codigo);
            return e;
        }
        ObligacionEmpresa nueva = ObligacionEmpresa.nueva(empresaId, codigo, usuarioId);
        empresaRepo.save(nueva);
        audit.log("obligacion_activada", "obligacion_empresa", nueva.getId(),
            "empresa_id=" + empresaId + " codigo=" + codigo);
        return nueva;
    }

    @Transactional
    public void desactivar(UUID empresaId, String codigo) {
        empresaRepo.findByEmpresaIdAndObligacionCodigo(empresaId, codigo).ifPresent(e -> {
            e.desactivar();
            empresaRepo.save(e);
            audit.log("obligacion_desactivada", "obligacion_empresa", e.getId(),
                "empresa_id=" + empresaId + " codigo=" + codigo);
        });
    }
}
