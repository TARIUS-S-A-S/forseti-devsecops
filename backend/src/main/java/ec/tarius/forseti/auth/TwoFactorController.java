package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/2fa")
public class TwoFactorController {

    private final TwoFactorService twoFactor;
    private final AuditLogger audit;

    public TwoFactorController(TwoFactorService twoFactor, AuditLogger audit) {
        this.twoFactor = twoFactor;
        this.audit = audit;
    }

    /** Genera secret + QR para que el usuario lo escanee con su app TOTP. */
    @PostMapping("/setup")
    @PreAuthorize("isAuthenticated()")
    public TwoFactorService.SetupData setup(@AuthenticationPrincipal Usuario user) {
        if (user == null) throw new AppException(ErrorCode.NO_AUTORIZADO, "No autenticado");
        return twoFactor.iniciarSetup(user.getId());
    }

    /** Confirma el código TOTP y activa 2FA. */
    @PostMapping("/confirm")
    @PreAuthorize("isAuthenticated()")
    public void confirm(@AuthenticationPrincipal Usuario user, @RequestBody ConfirmRequest req) {
        if (user == null) throw new AppException(ErrorCode.NO_AUTORIZADO, "No autenticado");
        twoFactor.confirmarSetup(user.getId(), req.secret(), req.code());
        audit.log("2fa_activado", "usuario", user.getId(), "Confirmación TOTP exitosa");
    }

    /** Desactiva 2FA (requiere password actual). Sprint 2: agregar re-auth con password. */
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public void disable(@AuthenticationPrincipal Usuario user) {
        if (user == null) throw new AppException(ErrorCode.NO_AUTORIZADO, "No autenticado");
        twoFactor.desactivar(user.getId());
        audit.log("2fa_desactivado", "usuario", user.getId(), null);
    }

    /**
     * Pausa o reanuda el pedido de 2FA al iniciar sesión SIN desactivar el 2FA.
     * El secret queda guardado; cuando reanudás, no hay que escanear QR de nuevo.
     */
    @PatchMapping("/login-required")
    @PreAuthorize("isAuthenticated()")
    public void setLoginRequired(@AuthenticationPrincipal Usuario user,
                                  @RequestBody LoginRequiredRequest req) {
        if (user == null) throw new AppException(ErrorCode.NO_AUTORIZADO, "No autenticado");
        twoFactor.setLoginRequired(user.getId(), req.required());
        audit.log(req.required() ? "2fa_login_reanudado" : "2fa_login_pausado",
            "usuario", user.getId(), null);
    }

    public record ConfirmRequest(@NotBlank String secret, @NotBlank String code) {}
    public record LoginRequiredRequest(boolean required) {}
}
