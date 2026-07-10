package ec.tarius.forseti.shared.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoBoxServiceTest {

    private CryptoBoxService service;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String keyB64 = Base64.getEncoder().encodeToString(key);
        service = new CryptoBoxService(keyB64);
        // Forzar init() sin Spring
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    @Test
    void cifra_y_descifra_recupera_el_mismo_plaintext() {
        byte[] plaintext = "hola mundo — contenido del .p12".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = service.cifrar(plaintext);
        byte[] descifrado = service.descifrar(cipher);
        assertThat(descifrado).isEqualTo(plaintext);
    }

    @Test
    void dos_cifrados_del_mismo_plaintext_dan_ciphertexts_distintos() {
        byte[] plaintext = "secret".getBytes();
        byte[] a = service.cifrar(plaintext);
        byte[] b = service.cifrar(plaintext);
        // Nonces distintos garantizan ciphertexts distintos
        assertThat(a).isNotEqualTo(b);
        // Ambos descifran al mismo plaintext
        assertThat(service.descifrar(a)).isEqualTo(plaintext);
        assertThat(service.descifrar(b)).isEqualTo(plaintext);
    }

    @Test
    void manipular_el_ciphertext_lanza_excepcion() {
        byte[] cipher = service.cifrar("contenido sensible".getBytes());
        // Flip un byte del medio (parte del ciphertext o del tag)
        cipher[cipher.length / 2] ^= 0xFF;
        assertThatThrownBy(() -> service.descifrar(cipher))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Fallo al descifrar");
    }

    @Test
    void payload_demasiado_corto_lanza_excepcion() {
        assertThatThrownBy(() -> service.descifrar(new byte[10]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void llave_de_longitud_incorrecta_falla_en_init() {
        CryptoBoxService bad = new CryptoBoxService(Base64.getEncoder().encodeToString(new byte[10]));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(bad, "init"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void llave_no_base64_falla_en_init() {
        CryptoBoxService bad = new CryptoBoxService("no es base64!!! @@@");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(bad, "init"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("base64");
    }
}
