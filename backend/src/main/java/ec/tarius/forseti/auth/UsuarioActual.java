package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import ec.tarius.forseti.shared.tenant.TenantContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Resolución del usuario autenticado actual (vía SecurityContext) y de la empresa activa
 * (vía TenantContext). Helper para evitar repetir el patrón en cada controller.
 */
public final class UsuarioActual {

    private UsuarioActual() {}

    public static UUID idObligatorio() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Usuario u)) {
            throw new AppException(ErrorCode.NO_AUTORIZADO, "No autenticado");
        }
        return u.getId();
    }

    public static Usuario obligatorio() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Usuario u)) {
            throw new AppException(ErrorCode.NO_AUTORIZADO, "No autenticado");
        }
        return u;
    }

    public static UUID empresaActivaObligatoria() {
        UUID id = TenantContext.getEmpresaId();
        if (id == null) {
            throw new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "No hay empresa activa en la sesión. Seleccioná o creá una primero.");
        }
        return id;
    }
}
