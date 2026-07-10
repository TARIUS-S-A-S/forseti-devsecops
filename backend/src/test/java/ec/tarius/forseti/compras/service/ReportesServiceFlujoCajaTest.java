package ec.tarius.forseti.compras.service;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.compras.domain.Compra;
import ec.tarius.forseti.compras.domain.IngresoManual;
import ec.tarius.forseti.compras.dto.ComprasDtos.FlujoCajaResponse;
import ec.tarius.forseti.compras.repo.CompraRepository;
import ec.tarius.forseti.compras.repo.IngresoManualRepository;
import ec.tarius.forseti.emision.ComprobanteRepository;
import ec.tarius.forseti.emision.domain.Comprobante;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests caja blanca de {@link ReportesService#flujoCaja}.
 *
 * Cubre:
 *   - Vacío: todos los repos devuelven listas vacías → saldo 0.
 *   - Solo compras pagadas, solo pendientes, mezcla.
 *   - Solo ingresos manuales cobrados / pendientes.
 *   - Facturas SRI AUTORIZADA suman, NC AUTORIZADA restan.
 *   - Compras anuladas EXCLUIDAS.
 *   - Ingresos anulados EXCLUIDOS.
 *   - Comprobantes NO autorizados (ABANDONADA / NO_AUTORIZADA / FIRMADA) EXCLUIDOS.
 *   - El repo se llama UNA SOLA VEZ por tabla (gate del refactor del double-read).
 *   - Defensa en profundidad: empresaId pasa explícito en todas las queries.
 *   - Resultados con scale=2 para presentación.
 */
class ReportesServiceFlujoCajaTest {

    private CompraRepository compraRepo;
    private IngresoManualRepository ingresoRepo;
    private ComprobanteRepository comprobanteRepo;
    private ReportesService sut;
    private MockedStatic<UsuarioActual> usuarioActualMock;
    private final UUID empresaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compraRepo = Mockito.mock(CompraRepository.class);
        ingresoRepo = Mockito.mock(IngresoManualRepository.class);
        comprobanteRepo = Mockito.mock(ComprobanteRepository.class);
        sut = new ReportesService(compraRepo, ingresoRepo, comprobanteRepo);
        usuarioActualMock = Mockito.mockStatic(UsuarioActual.class);
        usuarioActualMock.when(UsuarioActual::empresaActivaObligatoria).thenReturn(empresaId);
    }

    @AfterEach
    void tearDown() { usuarioActualMock.close(); }

    @Test
    void vacio_devuelve_todo_cero_y_saldo_cero() {
        mockTodoVacio();
        FlujoCajaResponse r = sut.flujoCaja(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(r.totalIngresosCobrados()).isEqualByComparingTo("0.00");
        assertThat(r.totalIngresosPendientes()).isEqualByComparingTo("0.00");
        assertThat(r.totalEgresosPagados()).isEqualByComparingTo("0.00");
        assertThat(r.totalEgresosPendientes()).isEqualByComparingTo("0.00");
        assertThat(r.saldoCobradoMenosPagado()).isEqualByComparingTo("0.00");
    }

    @Test
    void sin_fechas_usa_default_mes_actual_con_empresaId() {
        mockTodoVacio();
        sut.flujoCaja(null, null);

        org.mockito.ArgumentCaptor<LocalDate> desdeCap = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        Mockito.verify(compraRepo).findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.eq(empresaId), desdeCap.capture(), Mockito.any());
        // Default desde = día 1 del mes actual
        assertThat(desdeCap.getValue().getDayOfMonth()).isEqualTo(1);
    }

    @Test
    void compras_anuladas_se_excluyen_de_totales() {
        Compra pagada = compra(new BigDecimal("100"), Compra.EstadoPago.PAGADO, false);
        Compra anulada = compra(new BigDecimal("999"), Compra.EstadoPago.PAGADO, true);
        Compra pendiente = compra(new BigDecimal("50"), Compra.EstadoPago.PENDIENTE, false);
        Mockito.when(compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of(pagada, anulada, pendiente));
        mockResto();

        FlujoCajaResponse r = sut.flujoCaja(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(r.totalEgresosPagados()).isEqualByComparingTo("100.00");  // anulada NO suma
        assertThat(r.totalEgresosPendientes()).isEqualByComparingTo("50.00");
    }

    @Test
    void ingresos_manuales_anulados_se_excluyen() {
        IngresoManual cobrado = ingreso(new BigDecimal("200"), IngresoManual.EstadoCobro.COBRADO, false);
        IngresoManual anulado = ingreso(new BigDecimal("999"), IngresoManual.EstadoCobro.COBRADO, true);
        IngresoManual pendiente = ingreso(new BigDecimal("30"), IngresoManual.EstadoCobro.PENDIENTE, false);
        Mockito.when(ingresoRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of(cobrado, anulado, pendiente));
        mockComprasVacio();
        mockComprobantesVacio();

        FlujoCajaResponse r = sut.flujoCaja(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(r.totalIngresosCobrados()).isEqualByComparingTo("200.00");
        assertThat(r.totalIngresosPendientes()).isEqualByComparingTo("30.00");
    }

    @Test
    void comprobantes_no_autorizados_se_excluyen() {
        Comprobante autorizada = comprobante("FACTURA", new BigDecimal("500"), Comprobante.Estado.AUTORIZADA);
        Comprobante abandonada = comprobante("FACTURA", new BigDecimal("999"), Comprobante.Estado.ABANDONADA);
        Comprobante noAutorizada = comprobante("FACTURA", new BigDecimal("888"), Comprobante.Estado.NO_AUTORIZADA);
        Comprobante firmada = comprobante("FACTURA", new BigDecimal("777"), Comprobante.Estado.FIRMADA);
        Mockito.when(comprobanteRepo.findByEmpresaIdAndFechaEmisionBetween(
            Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of(autorizada, abandonada, noAutorizada, firmada));
        mockComprasVacio();
        mockIngresosVacio();

        FlujoCajaResponse r = sut.flujoCaja(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // Solo la AUTORIZADA suma — las demás se excluyen
        assertThat(r.totalIngresosCobrados()).isEqualByComparingTo("500.00");
    }

    @Test
    void notas_credito_autorizadas_restan_de_ingresos() {
        Comprobante factura = comprobante("FACTURA", new BigDecimal("1000"), Comprobante.Estado.AUTORIZADA);
        Comprobante nc = comprobante("NOTA_CREDITO", new BigDecimal("300"), Comprobante.Estado.AUTORIZADA);
        Mockito.when(comprobanteRepo.findByEmpresaIdAndFechaEmisionBetween(
            Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of(factura, nc));
        mockComprasVacio();
        mockIngresosVacio();

        FlujoCajaResponse r = sut.flujoCaja(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // 1000 (factura) - 300 (NC) = 700
        assertThat(r.totalIngresosCobrados()).isEqualByComparingTo("700.00");
    }

    @Test
    void escenario_completo_calcula_saldo_correcto() {
        // Pre-construir los mocks FUERA del when() para evitar UnfinishedStubbingException
        // (Mockito se confunde si se construye un mock dentro del argumento de otro when()).
        Compra compraPagada = compra(new BigDecimal("200"), Compra.EstadoPago.PAGADO, false);
        Compra compraPendiente = compra(new BigDecimal("80"), Compra.EstadoPago.PENDIENTE, false);
        IngresoManual ingCobrado = ingreso(new BigDecimal("100"), IngresoManual.EstadoCobro.COBRADO, false);
        IngresoManual ingPendiente = ingreso(new BigDecimal("40"), IngresoManual.EstadoCobro.PENDIENTE, false);
        Comprobante factura = comprobante("FACTURA", new BigDecimal("1000"), Comprobante.Estado.AUTORIZADA);
        Comprobante nc = comprobante("NOTA_CREDITO", new BigDecimal("200"), Comprobante.Estado.AUTORIZADA);

        Mockito.when(compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of(compraPagada, compraPendiente));
        Mockito.when(ingresoRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of(ingCobrado, ingPendiente));
        Mockito.when(comprobanteRepo.findByEmpresaIdAndFechaEmisionBetween(
            Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(List.of(factura, nc));

        FlujoCajaResponse r = sut.flujoCaja(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        // Ingresos cobrados = 100 (manual) + 1000 (factura) - 200 (NC) = 900
        assertThat(r.totalIngresosCobrados()).isEqualByComparingTo("900.00");
        assertThat(r.totalIngresosPendientes()).isEqualByComparingTo("40.00");
        assertThat(r.totalEgresosPagados()).isEqualByComparingTo("200.00");
        assertThat(r.totalEgresosPendientes()).isEqualByComparingTo("80.00");
        // Saldo = 900 - 200 = 700
        assertThat(r.saldoCobradoMenosPagado()).isEqualByComparingTo("700.00");
    }

    @Test
    void cada_repo_se_llama_UNA_sola_vez_por_invocacion_no_doble_read() {
        mockTodoVacio();
        sut.flujoCaja(null, null);

        Mockito.verify(compraRepo, Mockito.times(1))
            .findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
                Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(ingresoRepo, Mockito.times(1))
            .findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
                Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verify(comprobanteRepo, Mockito.times(1))
            .findByEmpresaIdAndFechaEmisionBetween(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void todos_los_montos_se_devuelven_con_scale_2() {
        // Mock que devuelva una compra con 100.5 (1 decimal) — debe escalar a 100.50
        Compra pagada = compra(new BigDecimal("100.5"), Compra.EstadoPago.PAGADO, false);
        Mockito.when(compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of(pagada));
        mockIngresosVacio();
        mockComprobantesVacio();

        FlujoCajaResponse r = sut.flujoCaja(null, null);

        assertThat(r.totalEgresosPagados().scale()).isEqualTo(2);
        assertThat(r.totalEgresosPagados()).isEqualByComparingTo(new BigDecimal("100.50"));
    }

    // ─── fixtures ──────────────────────────────────────────────────────

    private void mockTodoVacio() {
        mockComprasVacio();
        mockIngresosVacio();
        mockComprobantesVacio();
    }

    private void mockComprasVacio() {
        Mockito.when(compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of());
    }

    private void mockIngresosVacio() {
        Mockito.when(ingresoRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of());
    }

    private void mockComprobantesVacio() {
        Mockito.when(comprobanteRepo.findByEmpresaIdAndFechaEmisionBetween(
            Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of());
    }

    private void mockResto() {
        mockIngresosVacio();
        mockComprobantesVacio();
    }

    private Compra compra(BigDecimal total, Compra.EstadoPago estado, boolean anulada) {
        Compra c = Compra.nueva(empresaId, LocalDate.of(2026, 6, 10), "04", "1791111111001",
            "X", "001-001-000000001", "x", total.setScale(2, RoundingMode.HALF_UP),
            Compra.Origen.MANUAL);
        if (estado == Compra.EstadoPago.PAGADO) {
            c.marcarPagado(LocalDate.of(2026, 6, 12), "20");
        }
        if (anulada) {
            c.anular(UUID.randomUUID(), "test");
        }
        return c;
    }

    private IngresoManual ingreso(BigDecimal total, IngresoManual.EstadoCobro estado, boolean anulada) {
        IngresoManual i = IngresoManual.nuevo(empresaId, LocalDate.of(2026, 6, 5), "Cliente",
            "Servicio", total.setScale(2, RoundingMode.HALF_UP));
        if (estado == IngresoManual.EstadoCobro.COBRADO) {
            i.marcarCobrado(LocalDate.of(2026, 6, 8));
        }
        if (anulada) {
            i.anular(UUID.randomUUID(), "test");
        }
        return i;
    }

    private Comprobante comprobante(String tipo, BigDecimal total, Comprobante.Estado estado) {
        Comprobante c = Mockito.mock(Comprobante.class);
        Mockito.when(c.getTipoComprobante()).thenReturn(tipo);
        Mockito.when(c.getImporteTotal()).thenReturn(total.setScale(2, RoundingMode.HALF_UP));
        Mockito.when(c.getEstado()).thenReturn(estado);
        return c;
    }
}
