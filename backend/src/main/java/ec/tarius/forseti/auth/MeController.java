package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.auth.dto.AuthDtos.EmpresaMembership;
import ec.tarius.forseti.auth.dto.AuthDtos.MeResponse;
import ec.tarius.forseti.empresa.EmpresaRepository;
import ec.tarius.forseti.empresa.domain.Empresa;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class MeController {

    private final UsuarioEmpresaRepository usuarioEmpresaRepo;
    private final EmpresaRepository empresaRepo;

    public MeController(UsuarioEmpresaRepository usuarioEmpresaRepo, EmpresaRepository empresaRepo) {
        this.usuarioEmpresaRepo = usuarioEmpresaRepo;
        this.empresaRepo = empresaRepo;
    }

    /** Devuelve datos del usuario autenticado + sus empresas. */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)  // necesario para que TenantTransactionAdvice setee app.usuario_id antes del query con RLS
    public MeResponse me(@AuthenticationPrincipal Usuario user) {
        if (user == null) throw new AppException(ErrorCode.NO_AUTORIZADO, "No autenticado");
        var memberships = usuarioEmpresaRepo.findByUsuarioId(user.getId());
        List<EmpresaMembership> empresas = memberships.stream()
            .map(ue -> {
                Empresa e = empresaRepo.findById(ue.getEmpresaId()).orElse(null);
                String razon = e != null ? e.getRazonSocial() : "(eliminada)";
                return new EmpresaMembership(ue.getEmpresaId(), razon, ue.getRol().name());
            })
            .toList();
        return new MeResponse(
            user.getId(),
            user.getEmail(),
            user.getUsername(),
            user.getNombre(),
            user.isEmailVerificado(),
            user.isTotpActivo(),
            user.isTotpLoginRequired(),
            user.isDebeCambiarPassword(),
            empresas
        );
    }
}
