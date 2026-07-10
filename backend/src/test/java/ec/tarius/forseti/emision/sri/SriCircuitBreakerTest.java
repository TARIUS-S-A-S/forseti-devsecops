package ec.tarius.forseti.emision.sri;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SriCircuitBreakerTest {

    @Test
    void empieza_en_CLOSED_y_deja_pasar() {
        SriSoapClient.EstadoCircuito cb = new SriSoapClient.EstadoCircuito();
        assertThat(cb.getModo()).isEqualTo(SriSoapClient.EstadoCircuito.Modo.CLOSED);
        cb.permitirOLanzar("PRUEBAS"); // no debe lanzar
    }

    @Test
    void se_abre_tras_5_fallos_consecutivos_y_bloquea_llamadas_inmediatamente() {
        SriSoapClient.EstadoCircuito cb = new SriSoapClient.EstadoCircuito();

        for (int i = 0; i < 5; i++) {
            cb.registrarFallo();
        }

        assertThat(cb.getModo()).isEqualTo(SriSoapClient.EstadoCircuito.Modo.OPEN);
        assertThat(cb.getFallosConsecutivos()).isEqualTo(5);

        // La siguiente llamada debe ser rechazada inmediatamente con SriCircuitOpenException
        assertThatThrownBy(() -> cb.permitirOLanzar("PRUEBAS"))
            .isInstanceOf(SriSoapClient.SriCircuitOpenException.class)
            .hasMessageContaining("ABIERTO")
            .hasMessageContaining("PRUEBAS");
    }

    @Test
    void exito_resetea_contador_de_fallos() {
        SriSoapClient.EstadoCircuito cb = new SriSoapClient.EstadoCircuito();
        cb.registrarFallo();
        cb.registrarFallo();
        cb.registrarFallo();
        assertThat(cb.getFallosConsecutivos()).isEqualTo(3);

        cb.registrarExito();

        assertThat(cb.getFallosConsecutivos()).isEqualTo(0);
        assertThat(cb.getModo()).isEqualTo(SriSoapClient.EstadoCircuito.Modo.CLOSED);
    }

    @Test
    void desde_OPEN_un_exito_vuelve_a_CLOSED() {
        SriSoapClient.EstadoCircuito cb = new SriSoapClient.EstadoCircuito();
        for (int i = 0; i < 5; i++) cb.registrarFallo();
        assertThat(cb.getModo()).isEqualTo(SriSoapClient.EstadoCircuito.Modo.OPEN);

        cb.registrarExito();

        assertThat(cb.getModo()).isEqualTo(SriSoapClient.EstadoCircuito.Modo.CLOSED);
        assertThat(cb.getFallosConsecutivos()).isZero();
    }

    @Test
    void SriCircuitOpenException_es_subtipo_de_SriIOException() {
        // Es importante porque el job handler atrapa SriIOException para reintentar.
        // Si CircuitOpen no fuera subtipo, el job no sabría que es transient.
        SriSoapClient.SriCircuitOpenException ex = new SriSoapClient.SriCircuitOpenException("test");
        assertThat(ex).isInstanceOf(SriSoapClient.SriIOException.class);
    }
}
