package ec.tarius.forseti.compras;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ec.tarius.forseti.compras.domain.Compra;
import ec.tarius.forseti.compras.domain.CompraAdjunto;
import ec.tarius.forseti.compras.domain.CompraCategoria;
import ec.tarius.forseti.compras.domain.IngresoManual;
import ec.tarius.forseti.compras.dto.ComprasDtos.AnularRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearCompraRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.CrearIngresoManualRequest;
import ec.tarius.forseti.compras.dto.ComprasDtos.MarcarPagadoRequest;
import ec.tarius.forseti.compras.repo.CompraAdjuntoRepository;
import ec.tarius.forseti.compras.repo.CompraAdjuntoRepository.CompraAdjuntoCount;
import ec.tarius.forseti.compras.repo.CompraCategoriaRepository;
import ec.tarius.forseti.compras.service.CompraService;
import ec.tarius.forseti.compras.service.IngresoManualService;
import ec.tarius.forseti.compras.service.ReportesService;
import ec.tarius.forseti.shared.errors.AppException;
import ec.tarius.forseti.shared.errors.ErrorCode;
import ec.tarius.forseti.shared.errors.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests de los 3 controllers del Sprint 5 con MockMvc standalone.
 *
 * No verifica @PreAuthorize (sin SecurityFilterChain) — eso lo cubre el smoke E2E real
 * vía curl contra forseti.tarius.ec. Acá verificamos el mapeo HTTP correcto:
 *   - 200 happy + JSON correcto
 *   - 400 cuando @Valid falla (DTO inválido)
 *   - 404 cuando el service lanza AppException(NO_ENCONTRADO)
 *   - 409 cuando el service lanza AppException(DUPLICADA / YA_ANULADA)
 *   - Headers del CSV: Content-Type text/csv + Content-Disposition attachment
 *   - Headers del download adjunto
 *   - N+1 fix: listar 3 compras dispara 1 query agrupada de adjuntos (no 3)
 */
class ControllersMockMvcTest {

    private CompraService compraService;
    private IngresoManualService ingresoService;
    private ReportesService reportesService;
    private CompraAdjuntoRepository adjuntoRepo;
    private CompraCategoriaRepository categoriaRepo;

    private MockMvc mvc;
    private ObjectMapper json;
    private final UUID empresaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        compraService = Mockito.mock(CompraService.class);
        ingresoService = Mockito.mock(IngresoManualService.class);
        reportesService = Mockito.mock(ReportesService.class);
        adjuntoRepo = Mockito.mock(CompraAdjuntoRepository.class);
        categoriaRepo = Mockito.mock(CompraCategoriaRepository.class);

        CompraController compraCtl = new CompraController(compraService, adjuntoRepo, categoriaRepo);
        IngresoManualController ingresoCtl = new IngresoManualController(ingresoService);
        ReportesController reportesCtl = new ReportesController(reportesService);

        mvc = MockMvcBuilders.standaloneSetup(compraCtl, ingresoCtl, reportesCtl)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(jacksonConverter(), new ByteArrayHttpMessageConverter())
            .build();

        json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ─── CompraController ────────────────────────────────────────────────

    @Test
    void post_compras_happy_200_devuelve_compraResponse() throws Exception {
        CrearCompraRequest req = reqValido();
        Compra c = compraDeFixture();
        Mockito.when(compraService.crearManual(Mockito.any())).thenReturn(c);
        Mockito.when(compraService.toResponse(Mockito.any(), Mockito.any(), Mockito.eq(0)))
            .thenCallRealMethod();

        mvc.perform(post("/api/v1/compras")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.proveedorIdentificacion").value("1791234567001"))
            .andExpect(jsonPath("$.origen").value("MANUAL"));
    }

    @Test
    void post_compras_body_invalido_400() throws Exception {
        // req sin proveedorIdentificacion → @NotBlank falla → 400
        String body = "{\"fechaEmision\":\"2026-06-20\","
            + "\"proveedorTipoId\":\"04\",\"proveedorIdentificacion\":\"\","
            + "\"proveedorRazonSocial\":\"X\",\"tipoDocumento\":\"FACTURA\","
            + "\"numeroDocumento\":\"001-001-000000001\",\"concepto\":\"x\","
            + "\"baseIva15\":0,\"baseIva0\":0,\"baseNoObjeto\":0,\"baseExento\":0,"
            + "\"valorIva15\":0,\"retencionIr\":0,\"retencionIva\":0,\"total\":0}";

        mvc.perform(post("/api/v1/compras")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void post_compras_servicio_lanza_DUPLICADA_devuelve_409() throws Exception {
        Mockito.when(compraService.crearManual(Mockito.any()))
            .thenThrow(new AppException(ErrorCode.COMPRA_DUPLICADA, "ya existe"));

        mvc.perform(post("/api/v1/compras")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(reqValido())))
            .andExpect(status().isConflict());
    }

    @Test
    void get_compras_id_no_existente_404() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(compraService.obtener(id))
            .thenThrow(new AppException(ErrorCode.COMPRA_NO_ENCONTRADA, "x"));

        mvc.perform(get("/api/v1/compras/" + id))
            .andExpect(status().isNotFound());
    }

    @Test
    void post_compras_anular_happy_200() throws Exception {
        UUID id = UUID.randomUUID();
        Compra c = compraDeFixture();
        c.anular(UUID.randomUUID(), "test");
        Mockito.when(compraService.anular(Mockito.eq(id), Mockito.eq("Cargada por error")))
            .thenReturn(c);
        Mockito.when(adjuntoRepo.findByCompraIdOrderByCreadoAtAsc(Mockito.any())).thenReturn(List.of());
        Mockito.when(compraService.toResponse(Mockito.any(), Mockito.any(), Mockito.anyInt()))
            .thenCallRealMethod();

        mvc.perform(post("/api/v1/compras/" + id + "/anular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AnularRequest("Cargada por error"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.anulada").value(true));
    }

    @Test
    void post_compras_anular_ya_anulada_409() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(compraService.anular(Mockito.any(), Mockito.any()))
            .thenThrow(new AppException(ErrorCode.COMPRA_YA_ANULADA, "ya está"));

        mvc.perform(post("/api/v1/compras/" + id + "/anular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AnularRequest("Cargada por error"))))
            .andExpect(status().isConflict());
    }

    @Test
    void post_compras_marcarPagado_fecha_futura_400() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(compraService.marcarPagado(Mockito.any(), Mockito.any(), Mockito.any()))
            .thenThrow(new AppException(ErrorCode.VALIDACION, "fechaPago no puede ser futura"));

        MarcarPagadoRequest req = new MarcarPagadoRequest(LocalDate.now().plusDays(1), "20");
        mvc.perform(post("/api/v1/compras/" + id + "/marcar-pagado")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void get_compras_listar_3_compras_dispara_1_query_agrupada_de_adjuntos_no_n_plus_1() throws Exception {
        Compra c1 = compraDeFixture(); Compra c2 = compraDeFixture(); Compra c3 = compraDeFixture();
        Mockito.when(compraService.listar(Mockito.any(), Mockito.any()))
            .thenReturn(List.of(c1, c2, c3));
        Mockito.when(adjuntoRepo.countByCompraIds(Mockito.any())).thenReturn(List.of());
        Mockito.when(compraService.toResponse(Mockito.any(), Mockito.any(), Mockito.anyInt()))
            .thenCallRealMethod();

        mvc.perform(get("/api/v1/compras"))
            .andExpect(status().isOk());

        // Gate del fix #9: 1 sola query agrupada de adjuntos, no 3
        Mockito.verify(adjuntoRepo, Mockito.times(1)).countByCompraIds(Mockito.any());
        Mockito.verify(adjuntoRepo, Mockito.never()).findByCompraIdOrderByCreadoAtAsc(Mockito.any());
    }

    @Test
    void get_adjunto_descarga_con_content_disposition_attachment_no_formdata() throws Exception {
        UUID compraId = UUID.randomUUID();
        UUID adjuntoId = UUID.randomUUID();
        CompraAdjunto adj = Mockito.mock(CompraAdjunto.class);
        Mockito.when(adj.getCompraId()).thenReturn(compraId);
        Mockito.when(adj.getMimeTypeReal()).thenReturn("application/pdf");
        Mockito.when(adj.getNombreOriginal()).thenReturn("factura.pdf");
        Mockito.when(adj.getContenido()).thenReturn(new byte[]{1, 2, 3});

        Mockito.when(compraService.obtener(compraId)).thenReturn(compraDeFixture());
        Mockito.when(compraService.obtenerAdjunto(adjuntoId)).thenReturn(adj);

        mvc.perform(get("/api/v1/compras/" + compraId + "/adjuntos/" + adjuntoId))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("Content-Disposition", containsString("factura.pdf")));
    }

    // ─── IngresoManualController ─────────────────────────────────────────

    @Test
    void post_ingresos_happy_200() throws Exception {
        IngresoManual i = IngresoManual.nuevo(empresaId, LocalDate.of(2026, 6, 1),
            "Cliente", "Servicio", new BigDecimal("100"));
        Mockito.when(ingresoService.crear(Mockito.any())).thenReturn(i);
        Mockito.when(ingresoService.toResponse(Mockito.any())).thenCallRealMethod();

        CrearIngresoManualRequest req = new CrearIngresoManualRequest(
            LocalDate.of(2026, 6, 1), null, "Cliente", "Servicio",
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("100"), null);

        mvc.perform(post("/api/v1/ingresos-manuales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clienteRazonSocial").value("Cliente"));
    }

    @Test
    void post_ingresos_anular_ya_anulado_devuelve_409_con_code_INGRESO_YA_ANULADO() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(ingresoService.anular(Mockito.any(), Mockito.any()))
            .thenThrow(new AppException(ErrorCode.INGRESO_YA_ANULADO, "ya"));

        mvc.perform(post("/api/v1/ingresos-manuales/" + id + "/anular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AnularRequest("test"))))
            .andExpect(status().isConflict());
    }

    // ─── ReportesController ──────────────────────────────────────────────

    @Test
    void get_compras_csv_devuelve_text_csv_utf8_y_attachment() throws Exception {
        byte[] csv = "﻿Fecha;Tipo\n2026-06-01;FACTURA\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Mockito.when(reportesService.exportCsvCompras(Mockito.any(), Mockito.any())).thenReturn(csv);

        mvc.perform(get("/api/v1/reportes/compras.csv")
                .param("desde", "2026-06-01").param("hasta", "2026-06-30"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("text/csv")))
            .andExpect(header().string("Content-Type", containsString("UTF-8")))
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("Content-Disposition",
                containsString("compras-2026-06-01_2026-06-30.csv")));
    }

    @Test
    void get_ventas_csv_sin_fechas_usa_default_mes_actual_en_filename() throws Exception {
        Mockito.when(reportesService.exportCsvVentas(Mockito.any(), Mockito.any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/v1/reportes/ventas.csv"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("ventas-")));
    }

    // ─── fixtures ──────────────────────────────────────────────────────

    private CrearCompraRequest reqValido() {
        return new CrearCompraRequest(
            LocalDate.of(2026, 6, 20), "04", "1791234567001", "Proveedor Demo",
            "FACTURA", "001-001-000000123", "Servicio",
            null,
            new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("15"), BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("115"), true, null, null);
    }

    private Compra compraDeFixture() {
        return Compra.nueva(empresaId, LocalDate.of(2026, 6, 20),
            "04", "1791234567001", "Proveedor Demo",
            "001-001-000000123", "Servicio", new BigDecimal("115"),
            Compra.Origen.MANUAL);
    }

    /**
     * Jackson configurado igual que en producción (con JavaTimeModule para LocalDate/Instant).
     */
    private MappingJackson2HttpMessageConverter jacksonConverter() {
        MappingJackson2HttpMessageConverter c = new MappingJackson2HttpMessageConverter();
        ObjectMapper m = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        c.setObjectMapper(m);
        return c;
    }
}
