package ec.tarius.forseti.empresa;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.empresa.domain.Invitacion;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invitaciones")
public class InvitacionController {

    private final InvitacionRepository invitacionRepo;
    private final MiembroService miembroService;

    public InvitacionController(InvitacionRepository invitacionRepo,
                                  MiembroService miembroService) {
        this.invitacionRepo = invitacionRepo;
        this.miembroService = miembroService;
    }

    /**
     * Endpoint PÚBLICO: devuelve datos básicos de la invitación por token.
     * Sirve para que la UI muestre "Te invitaron a EmpresaX como CONTADORA" antes de loguear.
     * No expone datos sensibles más allá del nombre de la empresa y el rol propuesto.
     */
    @GetMapping("/{token}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<InvitacionPublicView> ver(@PathVariable String token) {
        Invitacion inv = invitacionRepo.findByToken(token)
            .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALIDO, "Invitación no encontrada"));

        boolean expirada = inv.getExpiraAt().isBefore(Instant.now());
        boolean usada = inv.getAceptadaAt() != null;
        boolean cancelada = inv.getCanceladaAt() != null;

        // Snapshot del nombre de la empresa al momento de invitar (denormalizado en la tabla)
        // — evita bypassear RLS de empresa en este endpoint público.
        String nombreEmpresa = inv.getEmpresaRazonSocial();

        return ResponseEntity.ok(new InvitacionPublicView(
            inv.getEmail(),
            inv.getNombreInvitado(),
            inv.getRol().name(),
            nombreEmpresa,
            inv.getExpiraAt(),
            expirada || usada || cancelada,
            expirada, usada, cancelada
        ));
    }

    /**
     * Aceptar: requiere usuario autenticado cuyo email coincida con el de la invitación.
     * Asocia al usuario actual a la empresa con el rol propuesto.
     */
    @PostMapping("/{token}/aceptar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AceptarResponse> aceptar(@PathVariable String token) {
        Usuario user = UsuarioActual.obligatorio();
        Invitacion inv = miembroService.aceptarInvitacion(token, user.getId(), user.getEmail());
        return ResponseEntity.ok(new AceptarResponse(inv.getEmpresaId(),
            "Te uniste a la empresa con rol " + inv.getRol()));
    }

    public record InvitacionPublicView(
        String email,
        String nombreInvitado,
        String rol,
        String nombreEmpresa,
        Instant expiraAt,
        boolean noDisponible,
        boolean expirada,
        boolean yaAceptada,
        boolean cancelada
    ) {}

    public record AceptarResponse(UUID empresaId, String mensaje) {}
}
