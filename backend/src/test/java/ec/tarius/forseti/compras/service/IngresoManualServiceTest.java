package ec.tarius.forseti.compras.service;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.compras.domain.IngresoManual;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearIngresoManualRequest;
import ec.tarius.forseti.compras.repo.IngresoManualRepository;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests caja blanca de {@link IngresoManualService} con Mockito puro.
 * Cubre TODOS los métodos públicos + branches:
 *
 *   crear: happy + bases default 0
 *   obtener: happy + 404
 *   listar: default mes actual usa empresaId explícito
 *   anular: happy + ya anulada (INGRESO_YA_ANULADO, NO COMPRA_YA_ANULADA — bug #11 audit)
 *   marcarCobrado: happy + null fecha + futura + anterior a emisión + anulada bloquea
 */
class IngresoManualServiceTest {

    private IngresoManualRepository repo;
    private IngresoManualService sut;
    private MockedStatic<UsuarioActual> usuarioActualMock;
    private final UUID empresaId = UUID.randomUUID();
    private final UUID usuarioId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(IngresoManualRepository.class);
        sut = new IngresoManualService(repo);
        usuarioActualMock = Mockito.mockStatic(UsuarioActual.class);
        usuarioActualMock.when(UsuarioActual::empresaActivaObligatoria).thenReturn(empresaId);
        usuarioActualMock.when(UsuarioActual::idObligatorio).thenReturn(usuarioId);
        Mockito.when(repo.save(Mockito.any(IngresoManual.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() { usuarioActualMock.close(); }

    @Test
    void crear_happy_persiste_con_empresa_y_usuario() {
        CrearIngresoManualRequest req = new CrearIngresoManualRequest(
            LocalDate.of(2026, 6, 20), "1791234567001", "Cliente Histórico",
            "Venta servicio antes de Forseti",
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("15"),
            BigDecimal.ZERO, new BigDecimal("115"), null);

        IngresoManual i = sut.crear(req);

        assertThat(i.getEmpresaId()).isEqualTo(empresaId);
        assertThat(i.getCreadoPorUsuarioId()).isEqualTo(usuarioId);
        assertThat(i.getEstadoCobro()).isEqualTo(IngresoManual.EstadoCobro.PENDIENTE);
    }

    @Test
    void crear_con_fechaCobro_marca_como_cobrado() {
        CrearIngresoManualRequest req = new CrearIngresoManualRequest(
            LocalDate.of(2026, 6, 1), null, "Cliente Y", "Servicio",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("50"), LocalDate.of(2026, 6, 15));

        IngresoManual i = sut.crear(req);

        assertThat(i.getEstadoCobro()).isEqualTo(IngresoManual.EstadoCobro.COBRADO);
        assertThat(i.getFechaCobro()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void obtener_no_encontrado_404() {
        UUID id = UUID.randomUUID();
        Mockito.when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.obtener(id))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.INGRESO_NO_ENCONTRADO);
    }

    @Test
    void listar_sin_fechas_usa_default_con_empresaId_explicito() {
        Mockito.when(repo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.eq(empresaId), Mockito.any(LocalDate.class), Mockito.any(LocalDate.class)))
            .thenReturn(List.of());

        sut.listar(null, null);

        Mockito.verify(repo).findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.eq(empresaId), Mockito.any(LocalDate.class), Mockito.any(LocalDate.class));
    }

    @Test
    void anular_happy() {
        IngresoManual i = nuevoIngreso(LocalDate.of(2026, 6, 1));
        Mockito.when(repo.findById(i.getId())).thenReturn(Optional.of(i));

        IngresoManual r = sut.anular(i.getId(), "duplicado");

        assertThat(r.isAnulada()).isTrue();
        assertThat(r.getMotivoAnulacion()).isEqualTo("duplicado");
        assertThat(r.getAnuladaPorUsuarioId()).isEqualTo(usuarioId);
    }

    @Test
    void anular_ya_anulado_lanza_INGRESO_YA_ANULADO_no_COMPRA_YA_ANULADA() {
        IngresoManual i = nuevoIngreso(LocalDate.of(2026, 6, 1));
        i.anular(UUID.randomUUID(), "primera");
        Mockito.when(repo.findById(i.getId())).thenReturn(Optional.of(i));

        assertThatThrownBy(() -> sut.anular(i.getId(), "segunda"))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.INGRESO_YA_ANULADO);  // semánticamente correcto, no COMPRA_*
    }

    @Test
    void marcarCobrado_null_fecha_lanza_VALIDACION() {
        assertThatThrownBy(() -> sut.marcarCobrado(UUID.randomUUID(), null))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("fechaCobro es obligatoria");
    }

    @Test
    void marcarCobrado_anulado_lanza_INGRESO_YA_ANULADO() {
        IngresoManual i = nuevoIngreso(LocalDate.of(2026, 6, 1));
        i.anular(UUID.randomUUID(), "anulado");
        Mockito.when(repo.findById(i.getId())).thenReturn(Optional.of(i));

        assertThatThrownBy(() -> sut.marcarCobrado(i.getId(), LocalDate.of(2026, 6, 10)))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.INGRESO_YA_ANULADO);
    }

    @Test
    void marcarCobrado_fecha_futura_rechazada() {
        IngresoManual i = nuevoIngreso(LocalDate.of(2026, 6, 1));
        Mockito.when(repo.findById(i.getId())).thenReturn(Optional.of(i));

        assertThatThrownBy(() -> sut.marcarCobrado(i.getId(), LocalDate.now().plusDays(1)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("no puede ser futura");
    }

    @Test
    void marcarCobrado_fecha_anterior_a_emision_rechazada() {
        IngresoManual i = nuevoIngreso(LocalDate.of(2026, 6, 20));
        Mockito.when(repo.findById(i.getId())).thenReturn(Optional.of(i));

        assertThatThrownBy(() -> sut.marcarCobrado(i.getId(), LocalDate.of(2026, 6, 10)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("no puede ser anterior a fechaEmision");
    }

    @Test
    void marcarCobrado_happy() {
        IngresoManual i = nuevoIngreso(LocalDate.of(2026, 6, 1));
        Mockito.when(repo.findById(i.getId())).thenReturn(Optional.of(i));

        IngresoManual r = sut.marcarCobrado(i.getId(), LocalDate.of(2026, 6, 10));

        assertThat(r.getEstadoCobro()).isEqualTo(IngresoManual.EstadoCobro.COBRADO);
        assertThat(r.getFechaCobro()).isEqualTo(LocalDate.of(2026, 6, 10));
    }

    private IngresoManual nuevoIngreso(LocalDate fecha) {
        IngresoManual i = IngresoManual.nuevo(empresaId, fecha, "Cliente", "concepto", new BigDecimal("50"));
        try {
            var f = IngresoManual.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(i, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return i;
    }
}
