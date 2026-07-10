package ec.tarius.forseti.shared.validacion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RucValidatorTest {

    // RUC privada construido con el algoritmo SRI (3er dígito=9, verificador calculado).
    @Test
    void ruc_juridica_privada_valido_pasa() {
        // 099 (Guayas, privada) + 234567 + verificador 5 + 001
        assertThat(RucValidator.esValido("0992345675001")).isTrue();
    }

    @Test
    void ruc_persona_natural_valido_pasa() {
        // 1710034065 es una cédula PN con verificador OK
        // (validamos el algoritmo: cédula + "001")
        assertThat(RucValidator.esValido("1710034065001")).isTrue();
    }

    @Test
    void longitud_distinta_a_13_falla() {
        assertThat(RucValidator.esValido("12345")).isFalse();
        assertThat(RucValidator.esValido("17932359760010")).isFalse();
    }

    @Test
    void no_termina_en_001_falla() {
        assertThat(RucValidator.esValido("0992345675002")).isFalse();
    }

    @Test
    void caracteres_no_numericos_fallan() {
        assertThat(RucValidator.esValido("ABC9235976001")).isFalse();
    }

    @Test
    void null_o_blank_falla() {
        assertThat(RucValidator.esValido(null)).isFalse();
        assertThat(RucValidator.esValido("")).isFalse();
        assertThat(RucValidator.esValido("             ")).isFalse();
    }

    @Test
    void provincia_invalida_falla() {
        // 25 no es provincia válida (1-24)
        assertThat(RucValidator.esValido("2593235976001")).isFalse();
        assertThat(RucValidator.esValido("0000000000001")).isFalse();
    }

    @Test
    void digito_verificador_incorrecto_falla() {
        // Cambiar el verificador (último de los 10 dígitos)
        assertThat(RucValidator.esValido("0992345670001")).isFalse();
    }
}
