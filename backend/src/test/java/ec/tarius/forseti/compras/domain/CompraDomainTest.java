package ec.tarius.forseti.compras.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 5 gate ③: lo financiero NO se borra, solo se anula.
 * Verifica que {@link Compra#anular} es soft-delete: marca anulada=true con motivo+
 * usuario+timestamp pero preserva todos los datos originales (RNF-6 trazabilidad).
 */
class CompraDomainTest {

    @Test
    void anular_NO_borra_preserva_todos_los_datos_originales() {
        Compra c = Compra.nueva(
            UUID.randomUUID(), LocalDate.of(2026, 6, 21),
            "04", "1791234567001", "Proveedor X",
            "001-001-000000123", "Servicio",
            new BigDecimal("115.00"), Compra.Origen.MANUAL);
        c.bases(new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("15.00"));

        UUID usuario = UUID.randomUUID();
        c.anular(usuario, "Factura cargada por error");

        // Anulada quedó marcada
        assertThat(c.isAnulada()).isTrue();
        assertThat(c.getAnuladaAt()).isNotNull();
        assertThat(c.getAnuladaPorUsuarioId()).isEqualTo(usuario);
        assertThat(c.getMotivoAnulacion()).isEqualTo("Factura cargada por error");

        // PERO los datos originales siguen intactos (auditoría)
        assertThat(c.getProveedorIdentificacion()).isEqualTo("1791234567001");
        assertThat(c.getNumeroDocumento()).isEqualTo("001-001-000000123");
        assertThat(c.getTotal()).isEqualByComparingTo(new BigDecimal("115.00"));
        assertThat(c.getBaseIva15()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(c.getValorIva15()).isEqualByComparingTo(new BigDecimal("15.00"));
    }

    @Test
    void anular_dos_veces_es_idempotente() {
        Compra c = Compra.nueva(UUID.randomUUID(), LocalDate.now(),
            "04", "1234567890001", "Y", "001-001-000000001", "x",
            BigDecimal.TEN, Compra.Origen.MANUAL);
        UUID u1 = UUID.randomUUID();
        c.anular(u1, "primera");
        var primerTimestamp = c.getAnuladaAt();
        // segunda anulación no cambia nada
        c.anular(UUID.randomUUID(), "segunda");
        assertThat(c.getAnuladaPorUsuarioId()).isEqualTo(u1);
        assertThat(c.getMotivoAnulacion()).isEqualTo("primera");
        assertThat(c.getAnuladaAt()).isEqualTo(primerTimestamp);
    }

    @Test
    void compra_recien_creada_no_esta_anulada() {
        Compra c = Compra.nueva(UUID.randomUUID(), LocalDate.now(),
            "04", "1234567890001", "Z", "001-001-000000001", "x",
            BigDecimal.ONE, Compra.Origen.MANUAL);
        assertThat(c.isAnulada()).isFalse();
        assertThat(c.getEstadoPago()).isEqualTo(Compra.EstadoPago.PENDIENTE);
    }
}
