package ec.tarius.forseti.auth;

import ec.tarius.forseti.auth.domain.Sesion;
import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.auth.domain.UsuarioEmpresa;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import ec.tarius.forseti.shared.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Servicio central de autenticación.
 * Maneja registro, login, logout, verificación de email, recovery, lockout.
 * 2FA TOTP delegado a TwoFactorService.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int MAX_INTENTOS_FALLIDOS = 5;
    private static final int MINUTOS_BLOQUEO = 15;
    private static final Duration TOKEN_VERIFICACION_TTL = Duration.ofHours(24);
    private static final Duration TOKEN_RECOVERY_TTL = Duration.ofMinutes(30);
    private static final Duration SESION_TTL = Duration.ofDays(7);

    private final UsuarioRepository usuarioRepo;
    private final UsuarioEmpresaRepository usuarioEmpresaRepo;
    private final SesionRepository sesionRepo;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    @PersistenceContext
    private EntityManager em;

    public AuthService(UsuarioRepository usuarioRepo,
                       UsuarioEmpresaRepository usuarioEmpresaRepo,
                       SesionRepository sesionRepo,
                       PasswordEncoder passwordEncoder) {
        this.usuarioRepo = usuarioRepo;
        this.usuarioEmpresaRepo = usuarioEmpresaRepo;
        this.sesionRepo = sesionRepo;
        this.passwordEncoder = passwordEncoder;
    }

    /** Registra un nuevo usuario. Devuelve el token de verificación (que se manda por correo). */
    @Transactional
    public RegisterResult registrar(String email, String nombre, String passwordPlano) {
        return registrar(email, nombre, passwordPlano, null);
    }

    /**
     * Registra con username opcional. Si se da, el usuario podrá loguearse con ese username
     * además de su email. El email sigue siendo obligatorio en self-service register.
     */
    @Transactional
    public RegisterResult registrar(String email, String nombre, String passwordPlano, String usernameOpt) {
        validarPassword(passwordPlano);

        String emailNorm = email.toLowerCase().trim();
        if (usuarioRepo.existsByEmail(emailNorm)) {
            // Respuesta intencional ambigua para evitar enum de emails (en el endpoint igual)
            throw new AppException(ErrorCode.EMAIL_YA_REGISTRADO, "El email ya está registrado");
        }

        String usernameNorm = null;
        if (usernameOpt != null && !usernameOpt.isBlank()) {
            usernameNorm = usernameOpt.trim().toLowerCase();
            if (!usernameNorm.matches("^[a-z0-9._-]{3,40}$")) {
                throw new AppException(ErrorCode.VALIDACION,
                    "Username inválido: 3-40 caracteres, solo minúsculas, números, punto, guión, guión bajo");
            }
            if (usuarioRepo.existsByUsername(usernameNorm)) {
                throw new AppException(ErrorCode.VALIDACION, "Ese nombre de usuario ya está tomado");
            }
        }

        String hash = passwordEncoder.encode(passwordPlano);
        Usuario u = Usuario.nuevo(emailNorm, nombre.trim(), hash);
        if (usernameNorm != null) u.asignarUsername(usernameNorm);

        String token = generarTokenAleatorio(32);
        u.asignarTokenVerificacion(token, Instant.now().plus(TOKEN_VERIFICACION_TTL));

        usuarioRepo.save(u);
        log.info("Usuario registrado: email={} username={} (id={})", emailNorm, usernameNorm, u.getId());

        return new RegisterResult(u.getId(), token);
    }

    /** Verifica el email con el token enviado. */
    @Transactional
    public void verificarEmail(String token) {
        Usuario u = usuarioRepo.findByVerificacionToken(token)
            .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALIDO, "Token de verificación inválido"));
        if (u.getVerificacionTokenExpiraAt() == null || u.getVerificacionTokenExpiraAt().isBefore(Instant.now())) {
            throw new AppException(ErrorCode.TOKEN_EXPIRADO, "Token de verificación expirado");
        }
        u.marcarEmailVerificado();
        usuarioRepo.save(u);
    }

    /**
     * Login. Acepta email o username como identificador.
     * Si el identificador contiene '@' se busca por email; sino por username.
     * Si el usuario tiene TOTP activo, requiere el código.
     */
    @Transactional
    public LoginResult login(String identificador, String passwordPlano, Optional<String> totpCode,
                             String ip, String userAgent) {
        String idNorm = identificador.toLowerCase().trim();
        Usuario u = (idNorm.contains("@") ? usuarioRepo.findByEmail(idNorm) : usuarioRepo.findByUsername(idNorm))
            .orElseThrow(() -> new AppException(ErrorCode.CREDENCIALES_INVALIDAS, "Identificador o contraseña incorrectos"));

        if (!u.isActivo()) {
            throw new AppException(ErrorCode.CUENTA_DESHABILITADA, "Cuenta deshabilitada");
        }

        if (u.isBloqueado()) {
            throw new AppException(ErrorCode.CUENTA_BLOQUEADA,
                "Cuenta bloqueada temporalmente — intentá más tarde");
        }

        if (!passwordEncoder.matches(passwordPlano, u.getPasswordHash())) {
            u.registrarIntentoFallido(MAX_INTENTOS_FALLIDOS, MINUTOS_BLOQUEO);
            usuarioRepo.save(u);
            throw new AppException(ErrorCode.CREDENCIALES_INVALIDAS, "Email o contraseña incorrectos");
        }

        if (!u.isEmailVerificado()) {
            throw new AppException(ErrorCode.EMAIL_NO_VERIFICADO,
                "Tenés que verificar tu email primero. Revisá tu bandeja.");
        }

        // Si TOTP está activo Y NO está pausado para login, requiere el código
        if (u.isTotpRequeridoEnLogin()) {
            if (totpCode.isEmpty()) {
                return LoginResult.requiere2FA(u.getId());
            }
            // Validación TOTP delegada al controller (que llama a TwoFactorService)
        }

        u.registrarLoginExitoso(ip);
        usuarioRepo.save(u);

        // Setear app.usuario_id en la TX actual para que la query a usuario_empresa (con RLS) devuelva las filas del user.
        setearUsuarioEnTx(u.getId());
        TenantContext.setUsuarioId(u.getId());

        // Crear sesión
        var empresas = usuarioEmpresaRepo.findByUsuarioId(u.getId());
        UUID empresaActivaId = empresas.isEmpty() ? null : empresas.get(0).getEmpresaId();

        String tokenPlano = generarTokenAleatorio(48); // 384 bits
        String tokenHash = sha256(tokenPlano);
        Sesion s = Sesion.nueva(u.getId(), empresaActivaId, tokenHash, ip, userAgent,
            Instant.now().plus(SESION_TTL));
        sesionRepo.save(s);

        log.info("Login exitoso: {} (id={})", idNorm, u.getId());
        return LoginResult.exitoso(u, empresas, tokenPlano);
    }

    /**
     * Continúa el login DESPUÉS de validar TOTP (en TwoFactorService).
     * El controller llama esto cuando el usuario pasó el código OTP correcto.
     */
    @Transactional
    public LoginResult continuarLoginConOtpVerificado(Usuario u, String ip, String userAgent) {
        u.registrarLoginExitoso(ip);
        usuarioRepo.save(u);

        setearUsuarioEnTx(u.getId());
        TenantContext.setUsuarioId(u.getId());

        var empresas = usuarioEmpresaRepo.findByUsuarioId(u.getId());
        UUID empresaActivaId = empresas.isEmpty() ? null : empresas.get(0).getEmpresaId();

        String tokenPlano = generarTokenAleatorio(48);
        String tokenHash = sha256(tokenPlano);
        Sesion s = Sesion.nueva(u.getId(), empresaActivaId, tokenHash, ip, userAgent,
            Instant.now().plus(SESION_TTL));
        sesionRepo.save(s);

        log.info("Login con 2FA exitoso: {} (id={})", u.getEmail(), u.getId());
        return LoginResult.exitoso(u, empresas, tokenPlano);
    }

    /** Encuentra una sesión activa por su token (el token plano, viene de la cookie). */
    @Transactional(readOnly = true)
    public Optional<Sesion> sesionPorToken(String tokenPlano) {
        if (tokenPlano == null || tokenPlano.isBlank()) return Optional.empty();
        return sesionRepo.findByTokenHash(sha256(tokenPlano))
            .filter(Sesion::isActiva);
    }

    @Transactional
    public void logout(String tokenPlano) {
        sesionRepo.findByTokenHash(sha256(tokenPlano)).ifPresent(s -> {
            s.revocar();
            sesionRepo.save(s);
        });
    }

    /** Inicia recovery de password. Devuelve el token (que se manda por correo). */
    @Transactional
    public Optional<String> iniciarRecovery(String email) {
        String emailNorm = email.toLowerCase().trim();
        return usuarioRepo.findByEmail(emailNorm).map(u -> {
            String token = generarTokenAleatorio(32);
            u.asignarTokenRecovery(token, Instant.now().plus(TOKEN_RECOVERY_TTL));
            usuarioRepo.save(u);
            return token;
        });
    }

    /**
     * Cambio de password "obligatorio" del primer login (usuarios creados sin email).
     * Requiere la password actual (temporal) + nueva.
     */
    @Transactional
    public void cambiarPasswordObligatorio(UUID usuarioId, String passwordActual, String passwordNueva) {
        validarPassword(passwordNueva);
        Usuario u = usuarioRepo.findById(usuarioId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "Usuario no encontrado"));
        if (!passwordEncoder.matches(passwordActual, u.getPasswordHash())) {
            throw new AppException(ErrorCode.CREDENCIALES_INVALIDAS, "La contraseña actual es incorrecta");
        }
        u.cambiarPassword(passwordEncoder.encode(passwordNueva));
        usuarioRepo.save(u);
    }

    /** Aplica nueva password con token de recovery. */
    @Transactional
    public void aplicarRecovery(String token, String passwordPlanoNuevo) {
        validarPassword(passwordPlanoNuevo);
        Usuario u = usuarioRepo.findByRecoveryToken(token)
            .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALIDO, "Token de recovery inválido"));
        if (u.getRecoveryTokenExpiraAt() == null || u.getRecoveryTokenExpiraAt().isBefore(Instant.now())) {
            throw new AppException(ErrorCode.TOKEN_EXPIRADO, "Token de recovery expirado");
        }
        u.cambiarPassword(passwordEncoder.encode(passwordPlanoNuevo));
        usuarioRepo.save(u);
        // Revocar todas las sesiones existentes
        sesionRepo.revocarTodasDelUsuario(u.getId(), Instant.now());
    }

    private void validarPassword(String password) {
        if (password == null || password.length() < 8) {
            throw new AppException(ErrorCode.PASSWORD_DEBIL,
                "La contraseña debe tener al menos 8 caracteres");
        }
        if (password.length() > 200) {
            throw new AppException(ErrorCode.PASSWORD_DEBIL, "Contraseña demasiado larga");
        }
    }

    public String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public String generarTokenAleatorio(int bytesLen) {
        byte[] buf = new byte[bytesLen];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * Setea app.usuario_id dentro de la TX actual para que las policies RLS
     * que dependen de current_usuario_id() devuelvan las filas correctas durante el login.
     * Sin esto, la query a usuario_empresa devolvería 0 filas porque current_usuario_id() es NULL.
     */
    private void setearUsuarioEnTx(UUID usuarioId) {
        em.createNativeQuery("SELECT set_config('app.usuario_id', :v, true)")
            .setParameter("v", usuarioId.toString())
            .getSingleResult();
    }

    public record RegisterResult(UUID usuarioId, String verificacionToken) {}

    public record LoginResult(
        boolean requiere2FA,
        UUID usuarioId,
        Usuario usuario,
        List<UsuarioEmpresa> empresas,
        String tokenSesion
    ) {
        public static LoginResult requiere2FA(UUID usuarioId) {
            return new LoginResult(true, usuarioId, null, List.of(), null);
        }
        public static LoginResult exitoso(Usuario u, List<UsuarioEmpresa> empresas, String token) {
            return new LoginResult(false, u.getId(), u, empresas, token);
        }
    }
}
