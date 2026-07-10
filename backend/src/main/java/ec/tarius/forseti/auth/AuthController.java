package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Sesion;
import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.auth.dto.AuthDtos.*;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.email.EmailService;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import ec.tarius.forseti.shared.ratelimit.RateLimiter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Nombre con __Host- (Sprint 1 fix A5): fuerza Secure + sin Domain + path=/. */
    public static final String SESSION_COOKIE = "__Host-FORSETI_SESSION";

    private final AuthService authService;
    private final TwoFactorService twoFactorService;
    private final RateLimiter rateLimiter;
    private final EmailService emailService;
    private final UsuarioRepository usuarioRepo;
    private final AuditLogger audit;

    public AuthController(AuthService authService, TwoFactorService twoFactorService,
                          RateLimiter rateLimiter, EmailService emailService,
                          UsuarioRepository usuarioRepo, AuditLogger audit) {
        this.authService = authService;
        this.twoFactorService = twoFactorService;
        this.rateLimiter = rateLimiter;
        this.emailService = emailService;
        this.usuarioRepo = usuarioRepo;
        this.audit = audit;
    }

    @PostMapping("/register")
    @PreAuthorize("permitAll()")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req,
                                                      HttpServletRequest http) {
        String ip = obtenerIp(http);
        if (!rateLimiter.registerPorIp(ip)) {
            throw new AppException(ErrorCode.RATE_LIMIT, "Demasiados registros desde tu IP — esperá 1 hora");
        }
        var result = authService.registrar(req.email(), req.nombre(), req.password(), req.username());
        emailService.enviarVerificacion(req.email(), req.nombre(), result.verificacionToken());
        audit.log("auth_registro", "usuario", result.usuarioId(), null,
            ip, http.getHeader("User-Agent"));
        log.info("Registro nuevo: usuario_id={}", result.usuarioId());
        return ResponseEntity.ok(new RegisterResponse(
            result.usuarioId(),
            "Cuenta creada. Revisá tu email para verificar.",
            Instant.now()
        ));
    }

    @PostMapping("/verify-email")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest req,
                                              HttpServletRequest http) {
        authService.verificarEmail(req.token());
        audit.log("auth_email_verificado", null, null, null,
            obtenerIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                                HttpServletRequest http,
                                                HttpServletResponse resp) {
        String ip = obtenerIp(http);
        String ua = Optional.ofNullable(http.getHeader("User-Agent")).orElse("unknown");

        // Rate limit: por IP y por email (anti-credential-stuffing)
        if (!rateLimiter.loginPorIp(ip)) {
            throw new AppException(ErrorCode.RATE_LIMIT,
                "Demasiados intentos desde tu IP. Esperá 5 minutos.");
        }
        if (!rateLimiter.loginPorEmail(req.email())) {
            throw new AppException(ErrorCode.RATE_LIMIT,
                "Demasiados intentos para este email. Esperá 5 minutos.");
        }

        var result = authService.login(req.email(), req.password(),
            Optional.ofNullable(req.otp()), ip, ua);

        if (result.requiere2FA()) {
            // Si vino con OTP, validarlo
            if (req.otp() != null && !req.otp().isBlank()) {
                String id = req.email().toLowerCase().trim();
                Usuario u = (id.contains("@") ? usuarioRepo.findByEmail(id) : usuarioRepo.findByUsername(id))
                    .orElseThrow();
                if (!twoFactorService.verificarCodigo(u, req.otp())) {
                    throw new AppException(ErrorCode.TOTP_INVALIDO, "Código 2FA inválido");
                }
                // OTP OK — re-login para crear sesión (sin TOTP esta vez para evitar loop)
                return loginPostOtp(u, ip, ua, resp);
            }
            // No setea cookie. El cliente debe re-llamar /login con el otp.
            return ResponseEntity.ok(LoginResponse.requiere2FA());
        }

        // Setear cookie de sesión
        Cookie cookie = construirCookie(result.tokenSesion(), 60 * 60 * 24 * 7); // 7 días
        resp.addCookie(cookie);

        audit.log("auth_login", "usuario", result.usuarioId(), null, ip, ua);

        var me = mapToMe(result.usuario(), result.empresas());
        return ResponseEntity.ok(LoginResponse.exitoso(me));
    }

    private ResponseEntity<LoginResponse> loginPostOtp(Usuario u, String ip, String ua, HttpServletResponse resp) {
        var result = authService.continuarLoginConOtpVerificado(u, ip, ua);
        Cookie cookie = construirCookie(result.tokenSesion(), 60 * 60 * 24 * 7);
        resp.addCookie(cookie);
        audit.log("auth_login_2fa", "usuario", u.getId(), null, ip, ua);
        return ResponseEntity.ok(LoginResponse.exitoso(mapToMe(result.usuario(), result.empresas())));
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated() || permitAll()")
    public ResponseEntity<Void> logout(HttpServletRequest http, HttpServletResponse resp) {
        Optional<String> tokenCookie = leerCookieSesion(http);
        tokenCookie.ifPresent(authService::logout);
        // Borrar cookie
        Cookie cookie = construirCookie("", 0);
        resp.addCookie(cookie);
        audit.log("auth_logout", null, null, null,
            obtenerIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/recovery")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> recovery(@Valid @RequestBody RecoveryRequest req,
                                          HttpServletRequest http) {
        String ip = obtenerIp(http);
        if (!rateLimiter.recoveryPorIp(ip) || !rateLimiter.recoveryPorEmail(req.email())) {
            // Igualmente devolvemos 204 (no revelar existencia) pero no procesamos
            return ResponseEntity.noContent().build();
        }
        Optional<String> token = authService.iniciarRecovery(req.email());
        if (token.isPresent()) {
            emailService.enviarRecovery(req.email(), token.get());
            log.info("Recovery email enviado a {}", req.email());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req,
                                                HttpServletRequest http) {
        authService.aplicarRecovery(req.token(), req.password());
        audit.log("auth_password_reset", null, null, null,
            obtenerIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /**
     * Cambio de contraseña de un usuario logueado. Útil cuando debeCambiarPassword=true
     * (usuarios creados con username + password temporal por el DUEÑO).
     */
    @PostMapping("/cambiar-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> cambiarPassword(@Valid @RequestBody CambiarPasswordRequest req,
                                                  HttpServletRequest http) {
        Usuario u = ec.tarius.forseti.auth.UsuarioActual.obligatorio();
        authService.cambiarPasswordObligatorio(u.getId(), req.passwordActual(), req.passwordNueva());
        audit.log("auth_password_cambiada", "usuario", u.getId(), null,
            obtenerIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    public record CambiarPasswordRequest(
        @jakarta.validation.constraints.NotBlank String passwordActual,
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Size(min = 8, max = 200) String passwordNueva
    ) {}

    // Helpers ─────────────────────────────────────

    private Cookie construirCookie(String value, int maxAgeSeconds) {
        Cookie c = new Cookie(SESSION_COOKIE, value);
        c.setHttpOnly(true);
        c.setSecure(true);          // requerido por __Host- prefix
        c.setPath("/");             // requerido por __Host- prefix
        c.setMaxAge(maxAgeSeconds);
        c.setAttribute("SameSite", "Strict");
        return c;
    }

    private Optional<String> leerCookieSesion(HttpServletRequest req) {
        if (req.getCookies() == null) return Optional.empty();
        for (Cookie c : req.getCookies()) {
            if (SESSION_COOKIE.equals(c.getName())) {
                return Optional.ofNullable(c.getValue());
            }
        }
        return Optional.empty();
    }

    private String obtenerIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private MeResponse mapToMe(Usuario u, java.util.List<ec.tarius.forseti.auth.domain.UsuarioEmpresa> empresas) {
        // En Sprint 1 no hay JOIN con empresa todavía — devolvemos solo ids. Sprint 2 lo enriquece.
        var memberships = empresas.stream()
            .map(ue -> new EmpresaMembership(ue.getEmpresaId(), "(empresa)", ue.getRol().name()))
            .toList();
        return new MeResponse(u.getId(), u.getEmail(), u.getUsername(), u.getNombre(),
            u.isEmailVerificado(), u.isTotpActivo(), u.isTotpLoginRequired(),
            u.isDebeCambiarPassword(), memberships);
    }
}
