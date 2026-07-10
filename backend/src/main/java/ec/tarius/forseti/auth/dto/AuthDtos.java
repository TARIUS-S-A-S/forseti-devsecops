package ec.tarius.forseti.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(min = 2, max = 200) String nombre,
        @NotBlank @Size(min = 8, max = 200) String password,
        @Size(max = 40) String username  // opcional: si se da, sirve como identificador alternativo de login
    ) {}

    /** El campo email acepta email o username; el AuthService decide por la presencia de '@'. */
    public record LoginRequest(
        @NotBlank @Size(max = 200) String email,
        @NotBlank @Size(min = 8, max = 200) String password,
        String otp
    ) {}

    public record RecoveryRequest(
        @NotBlank @Email @Size(max = 200) String email
    ) {}

    public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 200) String password
    ) {}

    public record VerifyEmailRequest(
        @NotBlank String token
    ) {}

    public record MeResponse(
        UUID id,
        String email,
        String username,
        String nombre,
        boolean emailVerificado,
        boolean tieneTotp,
        boolean totpLoginRequired,   // false = 2FA activo pero pausado para login
        boolean debeCambiarPassword,
        List<EmpresaMembership> empresas
    ) {}

    public record EmpresaMembership(
        UUID id,
        String razonSocial,
        String rol
    ) {}

    public record LoginResponse(
        String estado, // "EXITOSO" | "REQUIERE_2FA"
        MeResponse me
    ) {
        public static LoginResponse exitoso(MeResponse me) {
            return new LoginResponse("EXITOSO", me);
        }
        public static LoginResponse requiere2FA() {
            return new LoginResponse("REQUIERE_2FA", null);
        }
    }

    public record RegisterResponse(
        UUID usuarioId,
        String mensaje,
        Instant timestamp
    ) {}
}
