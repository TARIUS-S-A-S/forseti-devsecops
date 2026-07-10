package ec.tarius.forseti.compras.service;

import ec.tarius.forseti.compras.domain.Compra;
import ec.tarius.forseti.compras.repo.CompraRepository;
import ec.tarius.forseti.compras.repo.IngresoManualRepository;
import ec.tarius.forseti.emision.ComprobanteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 5 gate ②: el CSV abre limpio en Excel.
 *  - BOM UTF-8 al inicio (Excel ES-EC decodifica bien acentos)
 *  - separador ";"
 *  - header en español
 *  - movimientos anulados EXCLUIDOS
 */
class ReportesServiceCsvTest {

    private CompraRepository compraRepo;
    private IngresoManualRepository ingresoRepo;
    private ComprobanteRepository comprobanteRepo;
    private ReportesService sut;

    @BeforeEach
    void setUp() {
        compraRepo = Mockito.mock(CompraRepository.class);
        ingresoRepo = Mockito.mock(IngresoManualRepository.class);
        comprobanteRepo = Mockito.mock(ComprobanteRepository.class);
        sut = new ReportesService(compraRepo, ingresoRepo, comprobanteRepo);
    }

    @Test
    void csv_compras_tiene_bom_utf8_y_separador_punto_coma() {
        try (MockedStatic<ec.tarius.forseti.auth.UsuarioActual> mocked =
                 Mockito.mockStatic(ec.tarius.forseti.auth.UsuarioActual.class)) {
            mocked.when(ec.tarius.forseti.auth.UsuarioActual::empresaActivaObligatoria)
                  .thenReturn(UUID.randomUUID());

            Mockito.when(compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
                Mockito.any(UUID.class), Mockito.any(LocalDate.class), Mockito.any(LocalDate.class)))
                .thenReturn(List.of());

            byte[] csv = sut.exportCsvCompras(LocalDate.now(), LocalDate.now());

            // BOM UTF-8 al inicio: 0xEF 0xBB 0xBF
            assertThat(csv).hasSizeGreaterThan(3);
            assertThat(csv[0]).isEqualTo((byte) 0xEF);
            assertThat(csv[1]).isEqualTo((byte) 0xBB);
            assertThat(csv[2]).isEqualTo((byte) 0xBF);

            String contenido = new String(csv, StandardCharsets.UTF_8);
            assertThat(contenido).contains("Fecha;Tipo;Numero;Proveedor RUC");
        }
    }

    @Test
    void csv_compras_excluye_anuladas() {
        try (MockedStatic<ec.tarius.forseti.auth.UsuarioActual> mocked =
                 Mockito.mockStatic(ec.tarius.forseti.auth.UsuarioActual.class)) {
            mocked.when(ec.tarius.forseti.auth.UsuarioActual::empresaActivaObligatoria)
                  .thenReturn(UUID.randomUUID());

            Compra activa = Compra.nueva(UUID.randomUUID(), LocalDate.of(2026, 6, 1),
                "04", "1791111111001", "Proveedor Activo",
                "001-001-000000001", "Activa", new BigDecimal("100.00"),
                Compra.Origen.MANUAL);
            Compra anulada = Compra.nueva(UUID.randomUUID(), LocalDate.of(2026, 6, 2),
                "04", "1792222222001", "Proveedor Anulado",
                "001-001-000000002", "Anulada", new BigDecimal("999.00"),
                Compra.Origen.MANUAL);
            anulada.anular(UUID.randomUUID(), "test");

            Mockito.when(compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
                Mockito.any(UUID.class), Mockito.any(LocalDate.class), Mockito.any(LocalDate.class)))
                .thenReturn(List.of(activa, anulada));

            String contenido = new String(sut.exportCsvCompras(LocalDate.now(), LocalDate.now()),
                StandardCharsets.UTF_8);

            assertThat(contenido).contains("Proveedor Activo");
            assertThat(contenido).doesNotContain("Proveedor Anulado");
            assertThat(contenido).doesNotContain("999.00");
        }
    }
}
