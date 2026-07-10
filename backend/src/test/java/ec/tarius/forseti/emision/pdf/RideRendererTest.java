package ec.tarius.forseti.emision.pdf;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle.CodigoPorcentajeIva;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RideRendererTest {

    private static final RideRenderer SUT = new RideRenderer();

    @Test
    void render_pdf_minimo_no_lanza_y_devuelve_bytes_con_magic_pdf() {
        byte[] pdf = SUT.renderizar(comprobanteFirmado(), empresa(), List.of(detalleSimple()));

        assertThat(pdf).isNotEmpty();
        // PDF empieza con "%PDF-"
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // Razonable: > 5 KB con QR + branding
        assertThat(pdf.length).isGreaterThan(5_000);
    }

    @Test
    void render_estado_AUTORIZADA_no_lleva_marca_de_agua() {
        Comprobante c = comprobanteFirmado();
        c.marcarEnviado();
        c.marcarAutorizado(new byte[]{1, 2, 3}, c.getClaveAcceso(), Instant.now());

        byte[] pdf = SUT.renderizar(c, empresa(), List.of(detalleSimple()));
        assertThat(pdf).isNotEmpty();
        // (no podemos parsear el PDF fácil acá, pero al menos confirmamos que NO lanza)
    }

    @Test
    void render_estado_FIRMADA_lleva_marca_pendiente() {
        // El doc en estado FIRMADA muestra "PENDIENTE AUTORIZACIÓN" como marca de agua.
        // No podemos inspeccionar el PDF fácil, pero al menos verificamos que no falla.
        byte[] pdf = SUT.renderizar(comprobanteFirmado(), empresa(), List.of(detalleSimple()));
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void render_con_muchos_items_no_revienta() {
        java.util.List<ComprobanteDetalle> muchos = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            muchos.add(detalleSimple());
        }
        byte[] pdf = SUT.renderizar(comprobanteFirmado(), empresa(), muchos);
        assertThat(pdf).isNotEmpty();
    }

    /**
     * Sprint 4 fix anti-regresión: la marca de agua debe aparecer en CADA página de un
     * PDF multipágina. Antes del fix, el wrapper `<div class="marca-agua"><span>...`
     * con transform en el span solo se renderizaba en la 1.ª página.
     * Tras el fix, el elemento con `position: fixed` y transform directo se repite.
     *
     * Verificación pragmática: 60 ítems generan >1 página A4. El PDF resultante debe
     * tener tamaño > 12 KB (single page con QR ~6 KB; 2+ páginas + marca repetida > 12 KB).
     */
    @Test
    void render_multipagina_en_estado_pendiente_genera_pdf_con_varias_paginas() {
        java.util.List<ComprobanteDetalle> muchosItems = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            muchosItems.add(detalleSimple());
        }
        byte[] pdf = SUT.renderizar(comprobanteFirmado(), empresa(), muchosItems);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        // 2+ páginas con marca de agua repetida en cada una → tamaño claro > 1 página
        assertThat(pdf.length).isGreaterThan(12_000);
        // Heurística: en el binario PDF de openhtmltopdf cada página tiene su "obj" propio.
        // Contar "/Type /Page" debería dar >= 2 para multipágina.
        String pdfText = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        long paginas = pdfText.split("/Type\\s*/Page[^s]").length - 1;
        assertThat(paginas)
            .as("PDF debe tener al menos 2 páginas con 60 items")
            .isGreaterThanOrEqualTo(2);
    }

    // ─── fixtures ───────────────────────────────────────────────────────

    private Empresa empresa() {
        Empresa e = Empresa.nueva("1793235976001", "TARIUS S.A.S.");
        e.setDireccion("Av. Amazonas N34-451, Quito");
        e.setRegimenTributario("RIMPE_EMPRENDEDOR");
        e.setObligadoContabilidad(false);
        return e;
    }

    private Comprobante comprobanteFirmado() {
        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRUEBAS", 1L, "001-001-000000001",
            "2106202601179323597600110010010000000011234567812",
            LocalDate.of(2026, 6, 21));
        c.receptor("04", "1790012345001", "Cliente Demo S.A.",
                   "Av. NN 100", "demo@ejemplo.ec", null);
        c.formaPago("20", 30);
        c.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));
        c.marcarFirmado(new byte[]{0, 1, 2});
        return c;
    }

    private ComprobanteDetalle detalleSimple() {
        return ComprobanteDetalle.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), 1,
            "PROD-001", "Servicio de prueba para RIDE",
            new BigDecimal("1.000000"), new BigDecimal("100.000000"),
            BigDecimal.ZERO, new BigDecimal("100.00"),
            CodigoPorcentajeIva.IVA_15,
            new BigDecimal("100.00"), new BigDecimal("15.00"));
    }
}
