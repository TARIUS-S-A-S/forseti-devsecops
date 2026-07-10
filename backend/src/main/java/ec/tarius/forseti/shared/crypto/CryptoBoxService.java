package ec.tarius.forseti.shared.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado AES-256-GCM para columnas ultra-sensibles (.p12 + su password) — ADR-6.
 *
 * Llave maestra: viene de la env FORSETI_MASTER_KEY (base64 de 32 bytes). Vive FUERA de la DB.
 * Formato persistido: nonce(12B) || ciphertext || tag(16B). El tag va al final automáticamente
 * en el "ciphertext" de javax.crypto AES/GCM.
 *
 * Cada llamada a {@link #cifrar(byte[])} genera un nonce nuevo (SecureRandom) — un mismo plaintext
 * produce ciphertexts distintos, como debe ser.
 */
@Service
public class CryptoBoxService {

    private static final Logger log = LoggerFactory.getLogger(CryptoBoxService.class);

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final String masterKeyB64;
    private SecretKeySpec masterKey;

    public CryptoBoxService(@Value("${forseti.master-key:}") String masterKeyB64) {
        this.masterKeyB64 = masterKeyB64;
    }

    @PostConstruct
    void init() {
        if (masterKeyB64 == null || masterKeyB64.isBlank()) {
            throw new IllegalStateException(
                "FORSETI_MASTER_KEY no configurada — generar con: " +
                "openssl rand -base64 32  y setear como variable de entorno");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(masterKeyB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("FORSETI_MASTER_KEY no es base64 válido", e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(
                "FORSETI_MASTER_KEY debe tener 32 bytes (256 bits) — actual: " + key.length);
        }
        this.masterKey = new SecretKeySpec(key, "AES");
        log.info("CryptoBoxService listo (AES-256-GCM, llave de 32 bytes).");
    }

    /**
     * Cifra los bytes dados. Devuelve nonce || ciphertext || tag.
     */
    public byte[] cifrar(byte[] plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("plaintext es null");
        try {
            byte[] nonce = new byte[NONCE_LEN];
            random.nextBytes(nonce);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE_LEN + ct.length)
                .put(nonce)
                .put(ct)
                .array();
        } catch (Exception e) {
            // No exponer el detalle al log (puede contener pistas) — solo el tipo
            throw new RuntimeException("Fallo al cifrar: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Descifra el payload (nonce || ciphertext || tag). Lanza si el tag GCM no valida (datos manipulados).
     */
    public byte[] descifrar(byte[] payload) {
        if (payload == null || payload.length < NONCE_LEN + 16) {
            throw new IllegalArgumentException("payload demasiado corto");
        }
        try {
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(payload, 0, nonce, 0, NONCE_LEN);
            int ctLen = payload.length - NONCE_LEN;
            byte[] ct = new byte[ctLen];
            System.arraycopy(payload, NONCE_LEN, ct, 0, ctLen);

            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            return c.doFinal(ct);
        } catch (Exception e) {
            throw new RuntimeException("Fallo al descifrar: " + e.getClass().getSimpleName(), e);
        }
    }
}
