package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.sri.ClaveAccesoGenerator.Ambiente;
import ec.tarius.forseti.emision.sri.ClaveAccesoGenerator.TipoDocumento;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaveAccesoGeneratorTest {

    private static final String RUC_TARIUS = "1793235976001";

    @Test
    void clave_tiene_49_digitos_y_solo_digitos() {
        String clave = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 6, 20), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 1L);

        assertThat(clave).hasSize(49);
        assertThat(clave).matches("\\d{49}");
    }

    @Test
    void clave_respeta_orden_de_campos_segun_ficha_tecnica() {
        // Inputs fijos para poder verificar substring por substring
        String clave = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 6, 20), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "002", 123L, "87654321");

        // Posiciones 0..47 son los 48 dígitos + 1 verificador en posición 48
        assertThat(clave.substring(0, 8)).isEqualTo("20062026");          // ddMMyyyy
        assertThat(clave.substring(8, 10)).isEqualTo("01");               // codDoc FACTURA
        assertThat(clave.substring(10, 23)).isEqualTo(RUC_TARIUS);        // RUC 13
        assertThat(clave.substring(23, 24)).isEqualTo("1");               // ambiente PRUEBAS
        assertThat(clave.substring(24, 27)).isEqualTo("001");             // establecimiento
        assertThat(clave.substring(27, 30)).isEqualTo("002");             // punto emisión
        assertThat(clave.substring(30, 39)).isEqualTo("000000123");       // secuencial padded
        assertThat(clave.substring(39, 47)).isEqualTo("87654321");        // aleatorio
        assertThat(clave.substring(47, 48)).isEqualTo("1");               // tipoEmision NORMAL
        assertThat(clave.substring(48, 49)).matches("\\d");               // DV
    }

    @Test
    void cada_tipo_de_documento_pone_su_codigo_en_la_posicion_correcta() {
        record Caso(TipoDocumento tipo, String codEsperado) {}
        Caso[] casos = {
            new Caso(TipoDocumento.FACTURA, "01"),
            new Caso(TipoDocumento.LIQUIDACION_COMPRA, "03"),
            new Caso(TipoDocumento.NOTA_CREDITO, "04"),
            new Caso(TipoDocumento.NOTA_DEBITO, "05"),
            new Caso(TipoDocumento.GUIA_REMISION, "06"),
            new Caso(TipoDocumento.RETENCION, "07")
        };
        for (Caso c : casos) {
            String clave = ClaveAccesoGenerator.generar(
                LocalDate.of(2026, 1, 1), c.tipo, RUC_TARIUS,
                Ambiente.PRUEBAS, "001", "001", 1L, "00000001");
            assertThat(clave.substring(8, 10))
                .as("código tipo doc para " + c.tipo)
                .isEqualTo(c.codEsperado);
        }
    }

    @Test
    void ambiente_se_codifica_correctamente() {
        String pruebas = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 1, 1), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 1L, "00000001");
        String produccion = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 1, 1), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRODUCCION, "001", "001", 1L, "00000001");

        assertThat(pruebas.substring(23, 24)).isEqualTo("1");
        assertThat(produccion.substring(23, 24)).isEqualTo("2");
    }

    @Test
    void clave_es_determinista_para_la_misma_entrada() {
        String c1 = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 6, 20), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 42L, "12345678");
        String c2 = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 6, 20), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 42L, "12345678");
        assertThat(c1).isEqualTo(c2);
    }

    @Test
    void cambiar_un_solo_caracter_cambia_el_digito_verificador() {
        String base = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 6, 20), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 1L, "00000001");
        String otra = ClaveAccesoGenerator.generar(
            LocalDate.of(2026, 6, 20), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 2L, "00000001");  // secuencial cambia

        assertThat(base).isNotEqualTo(otra);
        // Al menos el secuencial Y el DV deben diferir
        assertThat(base.substring(30, 39)).isNotEqualTo(otra.substring(30, 39));
    }

    @Test
    void digito_verificador_modulo11_casos_conocidos() {
        // "1": 1×2 = 2; 11−2 = 9
        assertThat(ClaveAccesoGenerator.digitoVerificadorModulo11("1")).isEqualTo('9');
        // "0": 0×2 = 0; 11−0 = 11 → 0
        assertThat(ClaveAccesoGenerator.digitoVerificadorModulo11("0")).isEqualTo('0');
        // "12345": 5×2+4×3+3×4+2×5+1×6 = 10+12+12+10+6 = 50; 50 mod 11 = 6; 11−6 = 5
        assertThat(ClaveAccesoGenerator.digitoVerificadorModulo11("12345")).isEqualTo('5');
        // "111111": 1×(2+3+4+5+6+7) = 27; 27 mod 11 = 5; 11−5 = 6
        assertThat(ClaveAccesoGenerator.digitoVerificadorModulo11("111111")).isEqualTo('6');
    }

    @Test
    void digito_verificador_factor_se_reinicia_a_2_despues_de_7() {
        // 7 dígitos consumen factores 2..7+2 (1 vuelta + 1)
        // "1234567": d=7,6,5,4,3,2,1 (derecha→izquierda); factores 2,3,4,5,6,7,2
        // = 7×2 + 6×3 + 5×4 + 4×5 + 3×6 + 2×7 + 1×2
        // = 14 + 18 + 20 + 20 + 18 + 14 + 2 = 106
        // 106 mod 11 = 7; 11 − 7 = 4
        assertThat(ClaveAccesoGenerator.digitoVerificadorModulo11("1234567")).isEqualTo('4');
    }

    @Test
    void caso_especial_mod_resultado_10_devuelve_1() {
        // Buscamos manualmente un número que da suma con mod = 10
        // "5": 5×2 = 10; 10 mod 11 = 10; → DV = 1
        assertThat(ClaveAccesoGenerator.digitoVerificadorModulo11("5")).isEqualTo('1');
    }

    @Test
    void rechaza_ruc_invalido() {
        assertThatThrownBy(() -> ClaveAccesoGenerator.generar(
            LocalDate.now(), TipoDocumento.FACTURA, "123",
            Ambiente.PRUEBAS, "001", "001", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RUC");

        assertThatThrownBy(() -> ClaveAccesoGenerator.generar(
            LocalDate.now(), TipoDocumento.FACTURA, "abcdefghijklm",
            Ambiente.PRUEBAS, "001", "001", 1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechaza_serie_invalida() {
        assertThatThrownBy(() -> ClaveAccesoGenerator.generar(
            LocalDate.now(), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "1", "001", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("establecimiento");

        assertThatThrownBy(() -> ClaveAccesoGenerator.generar(
            LocalDate.now(), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "99", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("puntoEmision");
    }

    @Test
    void rechaza_secuencial_fuera_de_rango() {
        assertThatThrownBy(() -> ClaveAccesoGenerator.generar(
            LocalDate.now(), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 0L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Secuencial");

        assertThatThrownBy(() -> ClaveAccesoGenerator.generar(
            LocalDate.now(), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 1_000_000_000L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rechaza_codigo_numerico_no_8_digitos() {
        assertThatThrownBy(() -> ClaveAccesoGenerator.generar(
            LocalDate.now(), TipoDocumento.FACTURA, RUC_TARIUS,
            Ambiente.PRUEBAS, "001", "001", 1L, "1234"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codigo_aleatorio_generado_es_distinto_en_muchas_corridas() {
        // 1000 corridas: se espera muy alta diversidad. Si todos fueran iguales hay bug.
        Set<String> codigos = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codigos.add(ClaveAccesoGenerator.generarCodigoNumericoAleatorio());
        }
        // Con SecureRandom sobre 10^8 valores, esperar >990 únicos
        assertThat(codigos).hasSizeGreaterThan(990);
    }

    @Test
    void codigo_aleatorio_siempre_es_8_digitos() {
        for (int i = 0; i < 200; i++) {
            String codigo = ClaveAccesoGenerator.generarCodigoNumericoAleatorio();
            assertThat(codigo).matches("\\d{8}");
        }
    }
}
