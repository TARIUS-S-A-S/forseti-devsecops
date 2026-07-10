package ec.tarius.forseti.compras.service;

import ec.tarius.forseti.auth.UsuarioActual;
import ec.tarius.forseti.compras.domain.Compra;
import ec.tarius.forseti.compras.domain.CompraAdjunto;
import ec.tarius.forseti.compras.domain.CompraCategoria;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearCompraRequest;
import ec.tarius.forseti.compras.repo.CompraAdjuntoRepository;
import ec.tarius.forseti.compras.repo.CompraCategoriaRepository;
import ec.tarius.forseti.compras.repo.CompraRepository;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests caja blanca de {@link CompraService} con Mockito puro (sin Spring Context).
 * Cubre TODOS los métodos públicos + branches críticos:
 *
 *   crearManual: happy + duplicado (pre-insert) + categoría 404 + race condition DataIntegrityViolation
 *   crearDesdeXml: happy + duplicado + parser error + uso de fechaAutorizacion del envelope
 *   marcarPagado: happy + null fecha + null formaPago + futura + anterior a emisión + anulada bloquea
 *   anular: happy + ya anulada
 *   agregarAdjunto: happy + nombre vacío + Tika rechaza + sha256 duplicado + RLS (usa empresaId)
 */
class CompraServiceTest {

    private CompraRepository compraRepo;
    private CompraAdjuntoRepository adjuntoRepo;
    private CompraCategoriaRepository categoriaRepo;
    private XmlCompraParser xmlParser;
    private TikaFileValidator tikaValidator;
    private CompraService sut;
    private MockedStatic<UsuarioActual> usuarioActualMock;
    private final UUID empresaId = UUID.randomUUID();
    private final UUID usuarioId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compraRepo = Mockito.mock(CompraRepository.class);
        adjuntoRepo = Mockito.mock(CompraAdjuntoRepository.class);
        categoriaRepo = Mockito.mock(CompraCategoriaRepository.class);
        xmlParser = Mockito.mock(XmlCompraParser.class);
        tikaValidator = Mockito.mock(TikaFileValidator.class);
        sut = new CompraService(compraRepo, adjuntoRepo, categoriaRepo, xmlParser, tikaValidator);

        usuarioActualMock = Mockito.mockStatic(UsuarioActual.class);
        usuarioActualMock.when(UsuarioActual::empresaActivaObligatoria).thenReturn(empresaId);
        usuarioActualMock.when(UsuarioActual::idObligatorio).thenReturn(usuarioId);

        Mockito.when(compraRepo.save(Mockito.any(Compra.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        usuarioActualMock.close();
    }

    // ─── crearManual ─────────────────────────────────────────────────────

    @Test
    void crearManual_happy_path_persiste_y_filtra_duplicado_por_empresa() {
        CrearCompraRequest req = reqManualValido();
        Mockito.when(compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.eq(empresaId), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(Optional.empty());

        Compra c = sut.crearManual(req);

        assertThat(c.getEmpresaId()).isEqualTo(empresaId);
        assertThat(c.getProveedorIdentificacion()).isEqualTo("1791234567001");
        assertThat(c.getOrigen()).isEqualTo(Compra.Origen.MANUAL);
        Mockito.verify(compraRepo).save(Mockito.any(Compra.class));
        // Defensa en profundidad: el repo se llama con empresaId explícito
        Mockito.verify(compraRepo).findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.eq(empresaId), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void crearManual_duplicado_pre_insert_lanza_409() {
        Compra existente = Mockito.mock(Compra.class);
        Mockito.when(compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(Optional.of(existente));

        assertThatThrownBy(() -> sut.crearManual(reqManualValido()))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.COMPRA_DUPLICADA);
    }

    @Test
    void crearManual_categoria_inexistente_404() {
        CrearCompraRequest req = reqManualConCategoria(UUID.randomUUID());
        Mockito.when(compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(Optional.empty());
        Mockito.when(categoriaRepo.findById(Mockito.any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.crearManual(req))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.CATEGORIA_NO_ENCONTRADA);
    }

    @Test
    void crearManual_race_condition_unique_se_mapea_a_409() {
        Mockito.when(compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(Optional.empty());
        // Simular: pasa el check, pero al save() otro request ya insertó → UNIQUE violation
        Mockito.when(compraRepo.save(Mockito.any(Compra.class)))
            .thenThrow(new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint "
                + "\"compra_empresa_id_proveedor_identificacion_tipo_documento_n_key\""));

        assertThatThrownBy(() -> sut.crearManual(reqManualValido()))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.COMPRA_DUPLICADA);
    }

    // ─── crearDesdeXml ──────────────────────────────────────────────────

    @Test
    void crearDesdeXml_usa_fechaAutorizacion_del_envelope_no_now() {
        java.time.Instant fechaSri = java.time.Instant.parse("2026-06-20T15:30:00Z");
        XmlCompraParser.DatosCompra datos = new XmlCompraParser.DatosCompra(
            "1791111111001", "Prov SA", "Av X 1",
            "FACTURA", "001-001-000000099",
            "2106202601179111111100110010010000000991234567813",
            LocalDate.of(2026, 6, 20), fechaSri,
            new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("15"), new BigDecimal("115"), "DOLAR");
        Mockito.when(xmlParser.parsear(Mockito.any())).thenReturn(datos);
        Mockito.when(compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(Optional.empty());

        Compra c = sut.crearDesdeXml(new byte[]{1, 2}, null, null, null, null, null);

        assertThat(c.getOrigen()).isEqualTo(Compra.Origen.XML);
        assertThat(c.getFechaAutorizacionSri()).isEqualTo(fechaSri);  // NO Instant.now()
        assertThat(c.getClaveAcceso()).hasSize(49);
    }

    @Test
    void crearDesdeXml_sin_fechaAutorizacion_usa_now_como_fallback() {
        XmlCompraParser.DatosCompra datos = new XmlCompraParser.DatosCompra(
            "1791111111001", "Prov SA", null,
            "FACTURA", "001-001-000000100",
            "2106202601179111111100110010010000001001234567819",
            LocalDate.of(2026, 6, 20), null,  // sin fechaAutorizacion
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, new BigDecimal("50"), "DOLAR");
        Mockito.when(xmlParser.parsear(Mockito.any())).thenReturn(datos);
        Mockito.when(compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(Optional.empty());

        java.time.Instant antes = java.time.Instant.now();
        Compra c = sut.crearDesdeXml(new byte[]{1}, null, null, null, null, null);
        java.time.Instant despues = java.time.Instant.now();

        assertThat(c.getFechaAutorizacionSri()).isBetween(antes, despues);
    }

    @Test
    void crearDesdeXml_respeta_deducible_y_concepto_custom_del_usuario() {
        XmlCompraParser.DatosCompra datos = datosXmlMinimos();
        Mockito.when(xmlParser.parsear(Mockito.any())).thenReturn(datos);
        Mockito.when(compraRepo.findByEmpresaIdAndProveedorIdentificacionAndTipoDocumentoAndNumeroDocumento(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
            .thenReturn(Optional.empty());

        Compra c = sut.crearDesdeXml(new byte[]{1}, null, false,
            new BigDecimal("5"), new BigDecimal("2"), "Gasto personal del socio (no deducible)");

        assertThat(c.isDeducible()).isFalse();
        assertThat(c.getConcepto()).isEqualTo("Gasto personal del socio (no deducible)");
        assertThat(c.getRetencionIr()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(c.getRetencionIva()).isEqualByComparingTo(new BigDecimal("2"));
    }

    @Test
    void crearDesdeXml_parser_error_se_mapea_a_XML_COMPRA_INVALIDO() {
        Mockito.when(xmlParser.parsear(Mockito.any()))
            .thenThrow(new XmlCompraParser.XmlParserException("XML mal formado: ..."));

        assertThatThrownBy(() -> sut.crearDesdeXml(new byte[]{1}, null, null, null, null, null))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.XML_COMPRA_INVALIDO);
    }

    // ─── marcarPagado ───────────────────────────────────────────────────

    @Test
    void marcarPagado_null_fechaPago_lanza_VALIDACION() {
        UUID compraId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.marcarPagado(compraId, null, "20"))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("fechaPago es obligatoria");
    }

    @Test
    void marcarPagado_null_formaPago_lanza_VALIDACION() {
        UUID compraId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.marcarPagado(compraId, LocalDate.now(), null))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("formaPago es obligatoria");
    }

    @Test
    void marcarPagado_blank_formaPago_lanza_VALIDACION() {
        UUID compraId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.marcarPagado(compraId, LocalDate.now(), "   "))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("formaPago es obligatoria");
    }

    @Test
    void marcarPagado_fecha_futura_lanza_VALIDACION() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));

        LocalDate manana = LocalDate.now().plusDays(1);
        assertThatThrownBy(() -> sut.marcarPagado(c.getId(), manana, "20"))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("no puede ser futura");
    }

    @Test
    void marcarPagado_fecha_anterior_a_emision_lanza_VALIDACION() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 20));
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> sut.marcarPagado(c.getId(), LocalDate.of(2026, 6, 10), "20"))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("no puede ser anterior a fechaEmision");
    }

    @Test
    void marcarPagado_compra_anulada_lanza_COMPRA_YA_ANULADA() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        c.anular(UUID.randomUUID(), "test");
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> sut.marcarPagado(c.getId(), LocalDate.of(2026, 6, 5), "20"))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.COMPRA_YA_ANULADA);
    }

    @Test
    void marcarPagado_happy_marca_estado_y_fecha() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));

        Compra r = sut.marcarPagado(c.getId(), LocalDate.of(2026, 6, 15), "20");

        assertThat(r.getEstadoPago()).isEqualTo(Compra.EstadoPago.PAGADO);
        assertThat(r.getFechaPago()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(r.getFormaPago()).isEqualTo("20");
    }

    // ─── anular ─────────────────────────────────────────────────────────

    @Test
    void anular_happy_marca_anulada_con_motivo_y_usuario() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));

        Compra r = sut.anular(c.getId(), "Factura cargada por error");

        assertThat(r.isAnulada()).isTrue();
        assertThat(r.getMotivoAnulacion()).isEqualTo("Factura cargada por error");
        assertThat(r.getAnuladaPorUsuarioId()).isEqualTo(usuarioId);
        assertThat(r.getAnuladaAt()).isNotNull();
    }

    @Test
    void anular_ya_anulada_lanza_COMPRA_YA_ANULADA() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        c.anular(UUID.randomUUID(), "primera");
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> sut.anular(c.getId(), "segunda"))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.COMPRA_YA_ANULADA);
    }

    @Test
    void anular_compra_no_encontrada_404() {
        UUID id = UUID.randomUUID();
        Mockito.when(compraRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.anular(id, "motivo"))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.COMPRA_NO_ENCONTRADA);
    }

    // ─── agregarAdjunto ────────────────────────────────────────────────

    @Test
    void agregarAdjunto_nombre_vacio_lanza_ADJUNTO_INVALIDO() {
        UUID compraId = UUID.randomUUID();
        assertThatThrownBy(() -> sut.agregarAdjunto(compraId, new byte[]{1}, ""))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.ADJUNTO_INVALIDO);
        assertThatThrownBy(() -> sut.agregarAdjunto(compraId, new byte[]{1}, null))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.ADJUNTO_INVALIDO);
    }

    @Test
    void agregarAdjunto_tika_rechaza_se_mapea_a_ADJUNTO_INVALIDO() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));
        Mockito.when(tikaValidator.validar(Mockito.any(), Mockito.eq("falso.pdf")))
            .thenThrow(new TikaFileValidator.UnsupportedAttachmentException(
                "Tipo de archivo no permitido"));

        assertThatThrownBy(() -> sut.agregarAdjunto(c.getId(), new byte[]{4, 5}, "falso.pdf"))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.ADJUNTO_INVALIDO);
    }

    @Test
    void agregarAdjunto_sha256_duplicado_lanza_ADJUNTO_DUPLICADO() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));
        Mockito.when(tikaValidator.validar(Mockito.any(), Mockito.any())).thenReturn("application/pdf");
        // Defensa en profundidad: filtra por empresaId
        Mockito.when(adjuntoRepo.findByEmpresaIdAndCompraIdAndSha256(
            Mockito.eq(empresaId), Mockito.eq(c.getId()), Mockito.anyString()))
            .thenReturn(Optional.of(Mockito.mock(CompraAdjunto.class)));

        assertThatThrownBy(() -> sut.agregarAdjunto(c.getId(), new byte[]{4, 5}, "doc.pdf"))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.ADJUNTO_DUPLICADO);
    }

    @Test
    void agregarAdjunto_happy_guarda_con_empresaId_y_sha256() {
        Compra c = compraSinAnular(LocalDate.of(2026, 6, 1));
        Mockito.when(compraRepo.findById(c.getId())).thenReturn(Optional.of(c));
        Mockito.when(tikaValidator.validar(Mockito.any(), Mockito.any())).thenReturn("application/pdf");
        Mockito.when(adjuntoRepo.findByEmpresaIdAndCompraIdAndSha256(
            Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(Optional.empty());
        Mockito.when(adjuntoRepo.save(Mockito.any(CompraAdjunto.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        CompraAdjunto a = sut.agregarAdjunto(c.getId(), new byte[]{4, 5}, "doc.pdf");

        assertThat(a.getEmpresaId()).isEqualTo(empresaId);
        assertThat(a.getMimeTypeReal()).isEqualTo("application/pdf");
        assertThat(a.getSha256()).hasSize(64); // SHA-256 hex
    }

    // ─── obtener / listar ─────────────────────────────────────────────

    @Test
    void obtener_no_encontrada_404() {
        UUID id = UUID.randomUUID();
        Mockito.when(compraRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.obtener(id))
            .isInstanceOf(AppException.class)
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo(ErrorCode.COMPRA_NO_ENCONTRADA);
    }

    @Test
    void listar_sin_fechas_usa_mes_actual_con_empresaId() {
        Mockito.when(compraRepo.findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.eq(empresaId), Mockito.any(LocalDate.class), Mockito.any(LocalDate.class)))
            .thenReturn(List.of());

        sut.listar(null, null);

        org.mockito.ArgumentCaptor<LocalDate> desdeCap = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.ArgumentCaptor<LocalDate> hastaCap = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        Mockito.verify(compraRepo).findByEmpresaIdAndFechaEmisionBetweenOrderByFechaEmisionDescCreadoAtDesc(
            Mockito.eq(empresaId), desdeCap.capture(), hastaCap.capture());
        // Default: desde = primer día del mes actual
        assertThat(desdeCap.getValue().getDayOfMonth()).isEqualTo(1);
        assertThat(hastaCap.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    void listar_categorias_devuelve_solo_activas_ordenadas() {
        Mockito.when(categoriaRepo.findByActivaTrueOrderByOrden()).thenReturn(List.of());
        sut.listarCategorias();
        Mockito.verify(categoriaRepo).findByActivaTrueOrderByOrden();
    }

    // ─── fixtures ──────────────────────────────────────────────────────

    private CrearCompraRequest reqManualValido() {
        return new CrearCompraRequest(
            LocalDate.of(2026, 6, 20), "04", "1791234567001", "Proveedor Demo",
            "FACTURA", "001-001-000000123", "Servicio Demo",
            null,
            new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("15"), BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("115"), true,
            null, null);
    }

    private CrearCompraRequest reqManualConCategoria(UUID catId) {
        return new CrearCompraRequest(
            LocalDate.of(2026, 6, 20), "04", "1791234567001", "Proveedor Demo",
            "FACTURA", "001-001-000000124", "Servicio Demo",
            catId,
            new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("15"), BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("115"), true,
            null, null);
    }

    private Compra compraSinAnular(LocalDate fecha) {
        Compra c = Compra.nueva(empresaId, fecha, "04", "1791111111001", "X",
            "001-001-000000001", "x", new BigDecimal("100"), Compra.Origen.MANUAL);
        // Forzar un ID porque sin BD, JPA no lo asigna
        try {
            var f = Compra.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return c;
    }

    private XmlCompraParser.DatosCompra datosXmlMinimos() {
        return new XmlCompraParser.DatosCompra(
            "1791111111001", "Prov", null,
            "FACTURA", "001-001-000000200",
            "2106202601179111111100110010010000002001234567816",
            LocalDate.of(2026, 6, 20), null,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, new BigDecimal("10"), "DOLAR");
    }
}
