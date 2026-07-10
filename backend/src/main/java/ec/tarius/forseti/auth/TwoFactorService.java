package ec.tarius.forseti.auth;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import ec.tarius.forseti.auth.domain.Usuario;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Servicio TOTP RFC 6238 (compatible Google Authenticator, Authy, 1Password).
 * Sprint 1: setup + verify. Sprint 2: backup codes.
 */
@Service
public class TwoFactorService {

    private static final String ISSUER = "Forseti";

    private final UsuarioRepository usuarioRepo;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final CodeVerifier codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();

    public TwoFactorService(UsuarioRepository usuarioRepo) {
        this.usuarioRepo = usuarioRepo;
    }

    /** Genera secret nuevo (no lo guarda todavía — espera verify). */
    public SetupData iniciarSetup(UUID usuarioId) {
        Usuario u = usuarioRepo.findById(usuarioId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "Usuario no encontrado"));
        String secret = secretGenerator.generate();
        // Label: email si tiene, sino username, sino fallback. Es el "nombre" que el authenticator muestra.
        String label = u.getEmail() != null ? u.getEmail()
            : (u.getUsername() != null ? u.getUsername() : "forseti-user");
        QrData data = buildQrData(label, secret);
        String qrUri = data.getUri();
        String qrPngBase64 = generarQrPngBase64(data);
        return new SetupData(secret, qrUri, qrPngBase64);
    }

    /** Verifica el código y activa TOTP en el usuario. */
    @Transactional
    public void confirmarSetup(UUID usuarioId, String secret, String code) {
        Usuario u = usuarioRepo.findById(usuarioId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "Usuario no encontrado"));
        if (!codeVerifier.isValidCode(secret, code)) {
            throw new AppException(ErrorCode.TOTP_INVALIDO, "Código TOTP inválido");
        }
        u.activarTotp(secret);
        usuarioRepo.save(u);
    }

    /** Verifica un código contra el secret guardado del usuario. */
    public boolean verificarCodigo(Usuario u, String code) {
        if (!u.isTotpActivo() || u.getTotpSecret() == null || code == null) {
            return false;
        }
        return codeVerifier.isValidCode(u.getTotpSecret(), code);
    }

    @Transactional
    public void desactivar(UUID usuarioId) {
        Usuario u = usuarioRepo.findById(usuarioId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "Usuario no encontrado"));
        u.desactivarTotp();
        usuarioRepo.save(u);
    }

    /**
     * Pausa o reanuda el pedido de TOTP al iniciar sesión. NO toca el secret.
     * Si el usuario aún no activó 2FA, la operación es válida pero no tiene efecto observable.
     */
    @Transactional
    public void setLoginRequired(UUID usuarioId, boolean required) {
        Usuario u = usuarioRepo.findById(usuarioId)
            .orElseThrow(() -> new AppException(ErrorCode.NO_ENCONTRADO, "Usuario no encontrado"));
        if (required) u.reanudarTotpLogin();
        else u.pausarTotpLogin();
        usuarioRepo.save(u);
    }

    private QrData buildQrData(String label, String secret) {
        return new QrData.Builder()
            .label(label)
            .secret(secret)
            .issuer(ISSUER)
            .algorithm(HashingAlgorithm.SHA1)
            .digits(6)
            .period(30)
            .build();
    }

    /**
     * Genera el PNG base64 del QR usando los MISMOS datos del setup (label + secret).
     * Fix Sprint 2.5: antes generaba un QrData distinto con secret vacío → el QR escaneado
     * no llevaba el secret real y la app del usuario nunca generaba códigos válidos.
     */
    private String generarQrPngBase64(QrData data) {
        try {
            byte[] png = qrGenerator.generate(data);
            return Utils.getDataUriForImage(png, qrGenerator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new AppException(ErrorCode.ERROR_INTERNO, "Error generando QR", e);
        }
    }

    public record SetupData(String secret, String otpAuthUri, String qrPngBase64) {}
}
