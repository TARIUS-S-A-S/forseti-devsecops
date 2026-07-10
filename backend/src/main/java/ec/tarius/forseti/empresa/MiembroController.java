package ec.tarius.forseti.empresa;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.auth.UsuarioEmpresaRepository;
import ec.tarius.forseti.auth.domain.UsuarioEmpresa;
import ec.tarius.forseti.empresa.MiembroService.MiembroView;
import ec.tarius.forseti.empresa.MiembroService.InvitacionEmailResult;
import ec.tarius.forseti.empresa.MiembroService.UsuarioCreadoConUsernameResult;
import ec.tarius.forseti.empresa.domain.Invitacion;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/empresas/{empresaId}")
public class MiembroController {

    private final MiembroService service;
    private final UsuarioEmpresaRepository usuarioEmpresaRepo;

    public MiembroController(MiembroService service, UsuarioEmpresaRepository usuarioEmpresaRepo) {
        this.service = service;
        this.usuarioEmpresaRepo = usuarioEmpresaRepo;
    }

    // ─────────────────────────────────────────────────────────────
    // Listar
    // ─────────────────────────────────────────────────────────────

    @GetMapping("/miembros")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MiembroView>> listar(@PathVariable UUID empresaId) {
        verificarMiembro(empresaId);
        return ResponseEntity.ok(service.listarMiembros(empresaId));
    }

    @GetMapping("/invitaciones")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<InvitacionView>> listarInvitaciones(@PathVariable UUID empresaId) {
        verificarGestor(empresaId);
        var items = service.listarInvitacionesPendientes(empresaId).stream()
            .map(this::toView).toList();
        return ResponseEntity.ok(items);
    }

    // ─────────────────────────────────────────────────────────────
    // Crear miembros — dos vías
    // ─────────────────────────────────────────────────────────────

    @PostMapping("/miembros/email")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InvitarPorEmailResponse> invitarPorEmail(
            @PathVariable UUID empresaId,
            @Valid @RequestBody InvitarPorEmailRequest req) {
        UUID actor = UsuarioActual.idObligatorio();
        verificarGestor(empresaId);
        InvitacionEmailResult r = service.invitarPorEmail(empresaId, req.email(), req.nombre(),
            UsuarioEmpresa.Rol.valueOf(req.rol()), actor);
        return ResponseEntity.ok(new InvitarPorEmailResponse(
            r.invitacionId(), r.usuarioYaExistia(),
            r.usuarioYaExistia()
                ? "El usuario ya tenía cuenta; quedó asociado a la empresa."
                : "Invitación enviada. El correo llegará a " + req.email() + "."));
    }

    @PostMapping("/miembros/username")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CrearConUsernameResponse> crearConUsername(
            @PathVariable UUID empresaId,
            @Valid @RequestBody CrearConUsernameRequest req) {
        UUID actor = UsuarioActual.idObligatorio();
        verificarGestor(empresaId);
        UsuarioCreadoConUsernameResult r = service.crearConUsername(
            empresaId, req.username(), req.nombre(),
            UsuarioEmpresa.Rol.valueOf(req.rol()),
            req.passwordTemporal(), actor);
        // password temporal se devuelve UNA SOLA VEZ
        return ResponseEntity.ok(new CrearConUsernameResponse(
            r.usuarioId(), r.username(), r.passwordTemporal(),
            "Usuario creado. Anotá la contraseña: solo se muestra ahora."));
    }

    // ─────────────────────────────────────────────────────────────
    // Mutaciones — rol, expulsar, reset, cancelar
    // ─────────────────────────────────────────────────────────────

    @PatchMapping("/miembros/{usuarioId}/rol")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> cambiarRol(
            @PathVariable UUID empresaId,
            @PathVariable UUID usuarioId,
            @Valid @RequestBody CambiarRolRequest req) {
        UUID actor = UsuarioActual.idObligatorio();
        verificarGestor(empresaId);
        service.cambiarRol(empresaId, usuarioId, UsuarioEmpresa.Rol.valueOf(req.rol()), actor);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/miembros/{usuarioId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> quitar(@PathVariable UUID empresaId, @PathVariable UUID usuarioId) {
        UUID actor = UsuarioActual.idObligatorio();
        verificarGestor(empresaId);
        service.quitarMiembro(empresaId, usuarioId, actor);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/miembros/{usuarioId}/reset-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ResetPasswordResponse> resetPassword(
            @PathVariable UUID empresaId, @PathVariable UUID usuarioId) {
        UUID actor = UsuarioActual.idObligatorio();
        verificarGestor(empresaId);
        String nuevaPwd = service.resetPasswordUsuarioSinEmail(empresaId, usuarioId, actor);
        return ResponseEntity.ok(new ResetPasswordResponse(nuevaPwd,
            "Contraseña reseteada. Anotala: solo se muestra ahora."));
    }

    @DeleteMapping("/invitaciones/{invitacionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> cancelarInvitacion(@PathVariable UUID empresaId,
                                                    @PathVariable UUID invitacionId) {
        UUID actor = UsuarioActual.idObligatorio();
        verificarGestor(empresaId);
        service.cancelarInvitacion(empresaId, invitacionId, actor);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────
    // Verificación de permisos
    // ─────────────────────────────────────────────────────────────

    /** El usuario debe ser miembro (cualquier rol). */
    private void verificarMiembro(UUID empresaId) {
        UUID userId = UsuarioActual.idObligatorio();
        usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(userId, empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "No sos miembro de esta empresa"));
    }

    /** El usuario debe ser DUEÑO o ADMIN para gestionar miembros. */
    private void verificarGestor(UUID empresaId) {
        UUID userId = UsuarioActual.idObligatorio();
        UsuarioEmpresa.Rol rol = usuarioEmpresaRepo.findByUsuarioIdAndEmpresaId(userId, empresaId)
            .orElseThrow(() -> new AppException(ErrorCode.SIN_ACCESO_EMPRESA,
                "No sos miembro de esta empresa"))
            .getRol();
        if (rol != UsuarioEmpresa.Rol.DUENO && rol != UsuarioEmpresa.Rol.ADMIN) {
            throw new AppException(ErrorCode.PROHIBIDO,
                "Solo DUEÑO o ADMIN pueden gestionar miembros");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────

    public record InvitarPorEmailRequest(
        @NotBlank @jakarta.validation.constraints.Email @Size(max = 200) String email,
        @Size(max = 200) String nombre,
        @NotBlank @Pattern(regexp = "DUENO|CONTADORA|EMPLEADO|ADMIN") String rol
    ) {}

    public record InvitarPorEmailResponse(UUID invitacionId, boolean usuarioYaExistia, String mensaje) {}

    public record CrearConUsernameRequest(
        @NotBlank @Size(min = 3, max = 40) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String username,
        @NotBlank @Size(min = 2, max = 200) String nombre,
        @NotBlank @Pattern(regexp = "DUENO|CONTADORA|EMPLEADO|ADMIN") String rol,
        @Size(min = 0, max = 200) String passwordTemporal
    ) {}

    public record CrearConUsernameResponse(UUID usuarioId, String username,
                                            String passwordTemporal, String mensaje) {}

    public record CambiarRolRequest(
        @NotBlank @Pattern(regexp = "DUENO|CONTADORA|EMPLEADO|ADMIN") String rol
    ) {}

    public record ResetPasswordResponse(String passwordTemporal, String mensaje) {}

    public record InvitacionView(
        UUID id,
        String email,
        String nombreInvitado,
        String rol,
        Instant creadaAt,
        Instant expiraAt
    ) {}

    private InvitacionView toView(Invitacion i) {
        return new InvitacionView(i.getId(), i.getEmail(), i.getNombreInvitado(),
            i.getRol().name(), i.getCreadaAt(), i.getExpiraAt());
    }
}
