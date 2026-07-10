package ec.tarius.forseti.empresa;

import ec.tarius.forseti.auth.UsuarioEmpresaRepository;
import ec.tarius.forseti.auth.UsuarioRepository;
import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.auth.domain.UsuarioEmpresa;
import ec.tarius.forseti.empresa.domain.Invitacion;
import ec.tarius.forseti.shared.audit.AuditLogger;
import ec.tarius.forseti.shared.email.EmailService;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gestión de miembros y invitaciones por empresa (Sprint 2.5).
 *
 * Dos vías para sumar gente a una empresa:
 *   1. Invitación por email: el dueño da nombre + email + rol → se manda invitación con token.
 *      El invitado abre el link, se registra/loguea, y queda asociado a la empresa.
 *   2. Creación con username: el dueño da nombre + username + rol + password temporal.
 *      Útil cuando el empleado NO tiene correo propio (típico en empresas pequeñas).
 *      El usuario queda con debe_cambiar_password=true → forzado a cambiarla al primer login.
 *
 * Reglas:
 *   - Solo DUEÑO y ADMIN pueden invitar/crear/expulsar miembros.
 *   - No se puede quitar al último DUEÑO (trigger DB lo bloquea — esto es defensa en profundidad).
 *   - Las invitaciones expiran a 7 días.
 *   - Reset de password solo aplica a usuarios sin email (los con email usan /recovery normal).
 */
@Service
public class MiembroService {

    private static final Logger log = LoggerFactory.getLogger(MiembroService.class);
    private static final Duration INVITACION_TTL = Duration.ofDays(7);

    private final UsuarioRepository usuarioRepo;
    private final UsuarioEmpresaRepository usuarioEmpresaRepo;
    private final EmpresaRepository empresaRepo;
    private final InvitacionRepository invitacionRepo;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuditLogger audit;
    private final SecureRandom random = new SecureRandom();

    @PersistenceContext
    private EntityManager em;

    public MiembroService(UsuarioRepository usuarioRepo,
                           UsuarioEmpresaRepository usuarioEmpresaRepo,
                           EmpresaRepository empresaRepo,
                           InvitacionRepository invitacionRepo,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService,
                           AuditLogger audit) {
        this.usuarioRepo = usuarioRepo;
        this.usuarioEmpresaRepo = usuarioEmpresaRepo;
        this.empresaRepo = empresaRepo;
        this.invitacionRepo = invitacionRepo;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MiembroView> listarMiembros(UUID empresaId) {
        // El caller (MiembroController.verificarGestor) ya validó que el actor es DUEÑO/ADMIN
        // de esta empresa. Activamos el bit "soy gestor" para que la policy RLS (V9) permita
        // leer las membresías de OTROS usuarios de la misma empresa.
        marcarGestorEnTx(empresaId);
        return usuarioEmpresaRepo.findByEmpresaId(empresaId).stream()
            .map(ue -> {
                Usuario u = usuarioRepo.findById(ue.getUsuarioId()).orElse(null);
                if (u == null) return null;
                return new MiembroView(
                    ue.getId(), u.getId(), u.getNombre(), u.getEmail(), u.getUsername(),
                    ue.getRol(), u.isDebeCambiarPassword(), u.getUltimoLoginAt());
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<Invitacion> listarInvitacionesPendientes(UUID empresaId) {
        return invitacionRepo.findPendientes(empresaId);
    }

    /**
     * Setea app.gestor_de_empresa en la TX actual. La policy V9 de usuario_empresa lee este valor
     * y permite ver las membresías de la empresa indicada.
     * Llamar SIEMPRE después de validar en código que el actor es DUEÑO/ADMIN.
     */
    private void marcarGestorEnTx(UUID empresaId) {
        em.createNativeQuery("SELECT set_config('app.gestor_de_empresa', :v, true)")
            .setParameter("v", empresaId.toString())
            .getSingleResult();
    }

    /**
     * Invita a una persona por email. Si ya existe ese email en Forseti, se crea la membresía directo.
     * Si no, se manda un correo con un token; cuando lo acepte, se asocia a la empresa.
     */
    @Transactional
    public InvitacionEmailResult invitarPorEmail(UUID empresaId, String email, String nombreInvitado,
                                                  UsuarioEmpresa.Rol rol, UUID invitadaPor) {
        validarEmail(email);
        var empresa = empresaRepo.findById(empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.EMPRESA_NO_ENCONTRADA, "Empresa no encontrada"));

        String emailNorm = email.toLowerCase().trim();

        // ¿Ya hay invitación pendiente para ese email en esta empresa?
        if (invitacionRepo.findPendientePorEmail(empresaId, emailNorm).isPresent()) {
            throw new AppException(ErrorCode.VALIDACION,
                "Ya existe una invitación pendiente para ese email");
        }

        // ¿El usuario ya existe en Forseti?
        Optional<Usuario> existente = usuarioRepo.findByEmail(emailNorm);
        if (existente.isPresent()) {
            // Si ya es miembro, devolvemos error
            Usuario u = existente.get();
            marcarGestorEnTx(empresaId);
            if (usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(u.getId(), empresaId).isPresent()) {
                throw new AppException(ErrorCode.VALIDACION,
                    "Ese usuario ya es miembro de la empresa");
            }
            // Sumarlo directo (sin token)
            usuarioEmpresaRepo.save(UsuarioEmpresa.nueva(u.getId(), empresaId, rol));
            audit.log("miembro_agregado_directo", "usuario_empresa", u.getId(),
                "empresa_id=" + empresaId + " email=" + emailNorm + " rol=" + rol);
            return new InvitacionEmailResult(null, true, null);
        }

        // Crear invitación con token (guarda snapshot del nombre de la empresa)
        String token = generarToken();
        Invitacion inv = Invitacion.nueva(empresaId, empresa.getRazonSocial(),
            emailNorm, nombreInvitado, rol, token,
            invitadaPor, Instant.now().plus(INVITACION_TTL));
        invitacionRepo.save(inv);

        // Mandar correo
        emailService.enviarInvitacion(emailNorm, nombreInvitado, empresa.getRazonSocial(), token);

        audit.log("invitacion_enviada", "invitacion_empresa", inv.getId(),
            "empresa_id=" + empresaId + " email=" + emailNorm + " rol=" + rol);
        log.info("Invitación enviada empresa_id={} email={} rol={}", empresaId, emailNorm, rol);
        return new InvitacionEmailResult(inv.getId(), false, token);
    }

    /**
     * Crea un usuario con username + password temporal y lo asocia a la empresa.
     * Devuelve la password generada UNA SOLA VEZ (no se persiste en plano).
     */
    @Transactional
    public UsuarioCreadoConUsernameResult crearConUsername(UUID empresaId, String username,
                                                            String nombre, UsuarioEmpresa.Rol rol,
                                                            String passwordTemporalOpcional,
                                                            UUID creadaPor) {
        String userNorm = normalizarUsername(username);
        if (usuarioRepo.existsByUsername(userNorm)) {
            throw new AppException(ErrorCode.VALIDACION,
                "Ese nombre de usuario ya está tomado");
        }

        String passwordTemporal = (passwordTemporalOpcional == null || passwordTemporalOpcional.isBlank())
            ? generarPasswordTemporal()
            : passwordTemporalOpcional;
        if (passwordTemporal.length() < 8) {
            throw new AppException(ErrorCode.PASSWORD_DEBIL, "La contraseña temporal debe tener al menos 8 caracteres");
        }

        Usuario u = Usuario.nuevoConUsername(userNorm, nombre, passwordEncoder.encode(passwordTemporal));
        usuarioRepo.save(u);
        marcarGestorEnTx(empresaId);
        usuarioEmpresaRepo.save(UsuarioEmpresa.nueva(u.getId(), empresaId, rol));

        audit.log("miembro_creado_con_username", "usuario", u.getId(),
            "empresa_id=" + empresaId + " username=" + userNorm + " rol=" + rol);
        log.info("Usuario creado con username={} para empresa_id={}", userNorm, empresaId);
        return new UsuarioCreadoConUsernameResult(u.getId(), userNorm, passwordTemporal);
    }

    @Transactional
    public Invitacion aceptarInvitacion(String token, UUID usuarioAutenticadoId, String emailUsuario) {
        Invitacion inv = invitacionRepo.findByToken(token)
            .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALIDO, "Invitación no encontrada"));

        if (!inv.estaPendiente()) {
            if (inv.getExpiraAt().isBefore(Instant.now())) {
                throw new AppException(ErrorCode.TOKEN_EXPIRADO, "La invitación expiró");
            }
            throw new AppException(ErrorCode.TOKEN_INVALIDO, "La invitación ya no está activa");
        }

        // Solo el dueño del email puede aceptar (o cualquier usuario logueado si el email coincide).
        // No revelamos al atacante con qué email se invitó si el email del logueado no coincide.
        if (emailUsuario == null || !emailUsuario.equalsIgnoreCase(inv.getEmail())) {
            throw new AppException(ErrorCode.PROHIBIDO,
                "La invitación es para otro email. Iniciá sesión con la cuenta correcta.");
        }

        // Ya es miembro
        if (usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(usuarioAutenticadoId, inv.getEmpresaId()).isPresent()) {
            inv.aceptar(usuarioAutenticadoId);
            invitacionRepo.save(inv);
            return inv;
        }

        usuarioEmpresaRepo.save(UsuarioEmpresa.nueva(usuarioAutenticadoId, inv.getEmpresaId(), inv.getRol()));
        inv.aceptar(usuarioAutenticadoId);
        invitacionRepo.save(inv);

        audit.log("invitacion_aceptada", "invitacion_empresa", inv.getId(),
            "empresa_id=" + inv.getEmpresaId() + " usuario_id=" + usuarioAutenticadoId);
        return inv;
    }

    @Transactional
    public void cancelarInvitacion(UUID empresaId, UUID invitacionId, UUID canceladaPor) {
        Invitacion inv = invitacionRepo.findById(invitacionId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "Invitación no encontrada"));
        if (!inv.getEmpresaId().equals(empresaId)) {
            throw new AppException(ErrorCode.PROHIBIDO, "La invitación no pertenece a esta empresa");
        }
        inv.cancelar(canceladaPor);
        invitacionRepo.save(inv);
        audit.log("invitacion_cancelada", "invitacion_empresa", invitacionId, "empresa_id=" + empresaId);
    }

    @Transactional
    public void cambiarRol(UUID empresaId, UUID usuarioId, UsuarioEmpresa.Rol nuevoRol, UUID actorId) {
        marcarGestorEnTx(empresaId);
        UsuarioEmpresa ue = usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(usuarioId, empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "El usuario no es miembro de esta empresa"));
        if (ue.getRol() == nuevoRol) return;
        // Bloqueo trigger DB cubre "último DUEÑO" — acá hacemos check defensivo
        ue.setRol(nuevoRol);
        usuarioEmpresaRepo.save(ue);
        audit.log("miembro_rol_cambiado", "usuario_empresa", ue.getId(),
            "empresa_id=" + empresaId + " usuario_id=" + usuarioId + " nuevo_rol=" + nuevoRol + " actor=" + actorId);
    }

    @Transactional
    public void quitarMiembro(UUID empresaId, UUID usuarioId, UUID actorId) {
        marcarGestorEnTx(empresaId);
        UsuarioEmpresa ue = usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(usuarioId, empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "El usuario no es miembro de esta empresa"));
        if (usuarioId.equals(actorId)) {
            throw new AppException(ErrorCode.VALIDACION, "No te podés quitar a vos mismo");
        }
        usuarioEmpresaRepo.delete(ue);
        audit.log("miembro_quitado", "usuario_empresa", ue.getId(),
            "empresa_id=" + empresaId + " usuario_id=" + usuarioId + " actor=" + actorId);
    }

    /**
     * Reset de password para un usuario CREADO CON USERNAME (sin email).
     * Los que tienen email usan el flow /recovery normal.
     * Devuelve la nueva password temporal UNA SOLA VEZ.
     */
    @Transactional
    public String resetPasswordUsuarioSinEmail(UUID empresaId, UUID usuarioId, UUID actorId) {
        marcarGestorEnTx(empresaId);
        UsuarioEmpresa ue = usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(usuarioId, empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "El usuario no es miembro de esta empresa"));
        Usuario u = usuarioRepo.findById(ue.getUsuarioId())
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "Usuario no encontrado"));
        if (u.getEmail() != null) {
            throw new AppException(ErrorCode.VALIDACION,
                "Ese usuario tiene email. Pedí recovery por correo en lugar de reset.");
        }
        String nuevaPwd = generarPasswordTemporal();
        u.cambiarPassword(passwordEncoder.encode(nuevaPwd));
        u.marcarDebeCambiarPassword();
        usuarioRepo.save(u);
        audit.log("password_reset_username", "usuario", u.getId(),
            "empresa_id=" + empresaId + " actor=" + actorId);
        return nuevaPwd;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String generarToken() {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String generarPasswordTemporal() {
        // 12 caracteres alfanuméricos URL-safe; legible para dictar/transmitir
        byte[] buf = new byte[9];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String normalizarUsername(String s) {
        if (s == null || s.isBlank()) {
            throw new AppException(ErrorCode.VALIDACION, "Username obligatorio");
        }
        String norm = s.trim().toLowerCase();
        if (!norm.matches("^[a-z0-9._-]{3,40}$")) {
            throw new AppException(ErrorCode.VALIDACION,
                "Username inválido: 3-40 caracteres, solo letras minúsculas, números, punto, guión y guión bajo");
        }
        return norm;
    }

    private static void validarEmail(String email) {
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new AppException(ErrorCode.VALIDACION, "Email inválido");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Views / Results
    // ─────────────────────────────────────────────────────────────

    public record MiembroView(
        UUID membershipId,
        UUID usuarioId,
        String nombre,
        String email,
        String username,
        UsuarioEmpresa.Rol rol,
        boolean debeCambiarPassword,
        Instant ultimoLoginAt
    ) {}

    public record InvitacionEmailResult(
        UUID invitacionId,
        boolean usuarioYaExistia,
        String tokenSoloParaTests  // En prod NUNCA devolver el token al frontend; está acá para test e2e
    ) {}

    public record UsuarioCreadoConUsernameResult(
        UUID usuarioId,
        String username,
        String passwordTemporal
    ) {}
}
