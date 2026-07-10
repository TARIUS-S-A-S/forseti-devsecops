package ec.tarius.forseti.emision.sri;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoriaErrorSriTest {

    @Test
    void codigo_39_es_BUG_FIRMADOR_y_requiere_alerta() {
        // Si 39 aparece post-fix de Sprint 3 Fase E, es regresión seria.
        CategoriaErrorSri cat = CategoriaErrorSri.de("39");
        assertThat(cat).isEqualTo(CategoriaErrorSri.BUG_FIRMADOR);
        assertThat(cat.requiereAlerta()).isTrue();
        assertThat(cat.debeReintentar()).isFalse();
    }

    @Test
    void codigo_70_es_SRI_TRANSIENTE_y_debe_reintentar() {
        CategoriaErrorSri cat = CategoriaErrorSri.de("70");
        assertThat(cat).isEqualTo(CategoriaErrorSri.SRI_TRANSIENTE);
        assertThat(cat.debeReintentar()).isTrue();
        assertThat(cat.requiereAlerta()).isFalse();
    }

    @Test
    void codigo_35_es_DUPLICADO_no_reintenta() {
        CategoriaErrorSri cat = CategoriaErrorSri.de("35");
        assertThat(cat).isEqualTo(CategoriaErrorSri.DUPLICADO);
        assertThat(cat.debeReintentar()).isFalse();
    }

    @Test
    void codigos_de_datos_invalidos_43_52_65_68_no_reintentan() {
        for (String c : new String[]{"43", "52", "65", "68"}) {
            CategoriaErrorSri cat = CategoriaErrorSri.de(c);
            assertThat(cat).as("código %s", c).isEqualTo(CategoriaErrorSri.DATOS_INVALIDOS);
            assertThat(cat.debeReintentar()).as("código %s no debe reintentar", c).isFalse();
        }
    }

    @Test
    void codigo_null_o_vacio_es_DESCONOCIDO_y_no_reintenta() {
        assertThat(CategoriaErrorSri.de(null)).isEqualTo(CategoriaErrorSri.DESCONOCIDO);
        assertThat(CategoriaErrorSri.de("")).isEqualTo(CategoriaErrorSri.DESCONOCIDO);
        assertThat(CategoriaErrorSri.de("  ")).isEqualTo(CategoriaErrorSri.DESCONOCIDO);
        assertThat(CategoriaErrorSri.DESCONOCIDO.debeReintentar())
            .as("códigos desconocidos no reintentamos por seguridad").isFalse();
    }

    @Test
    void codigo_no_listado_es_DESCONOCIDO() {
        assertThat(CategoriaErrorSri.de("9999")).isEqualTo(CategoriaErrorSri.DESCONOCIDO);
    }

    @Test
    void trim_de_codigo_con_espacios_funciona() {
        assertThat(CategoriaErrorSri.de("  39  ")).isEqualTo(CategoriaErrorSri.BUG_FIRMADOR);
    }
}
