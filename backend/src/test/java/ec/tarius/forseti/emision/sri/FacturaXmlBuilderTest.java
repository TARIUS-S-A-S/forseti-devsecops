package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle.CodigoPorcentajeIva;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FacturaXmlBuilderTest {

    private static final String RUC_TARIUS = "1793235976001";

    @Test
    void root_tiene_id_comprobante_y_version_2_1_0() {
        Document doc = construirFacturaSimple();
        Element root = doc.getDocumentElement();
        assertThat(root.getTagName()).isEqualTo("factura");
        assertThat(root.getAttribute("id")).isEqualTo("comprobante");
        assertThat(root.getAttribute("version")).isEqualTo("2.1.0");
    }

    @Test
    void infoTributaria_tiene_todos_los_campos_obligatorios_en_orden() {
        Document doc = construirFacturaSimple();
        Element it = (Element) doc.getElementsByTagName("infoTributaria").item(0);
        assertThat(it).isNotNull();

        assertThat(texto(it, "ambiente")).isEqualTo("1");                    // PRUEBAS
        assertThat(texto(it, "tipoEmision")).isEqualTo("1");
        assertThat(texto(it, "razonSocial")).isEqualTo("TARIUS S.A.S.");
        assertThat(texto(it, "ruc")).isEqualTo(RUC_TARIUS);
        assertThat(texto(it, "claveAcceso")).hasSize(49);
        assertThat(texto(it, "codDoc")).isEqualTo("01");
        assertThat(texto(it, "estab")).isEqualTo("001");
        assertThat(texto(it, "ptoEmi")).isEqualTo("001");
        assertThat(texto(it, "secuencial")).isEqualTo("000000007");
        assertThat(texto(it, "dirMatriz")).isNotBlank();
    }

    @Test
    void infoTributaria_lleva_leyenda_rimpe_cuando_corresponde() {
        Empresa rimpe = empresa("RIMPE_EMPRENDEDOR", false);
        Empresa np = empresa("RIMPE_NP", false);
        Empresa general = empresa("GENERAL", false);

        Document docR = FacturaXmlBuilder.construir(rimpe, facturaBasica(rimpe), List.of(detalle()));
        Document docN = FacturaXmlBuilder.construir(np, facturaBasica(np), List.of(detalle()));
        Document docG = FacturaXmlBuilder.construir(general, facturaBasica(general), List.of(detalle()));

        Element itR = (Element) docR.getElementsByTagName("infoTributaria").item(0);
        Element itN = (Element) docN.getElementsByTagName("infoTributaria").item(0);
        Element itG = (Element) docG.getElementsByTagName("infoTributaria").item(0);

        assertThat(texto(itR, "contribuyenteRimpe")).isEqualTo("CONTRIBUYENTE RÉGIMEN RIMPE");
        assertThat(texto(itN, "contribuyenteRimpe")).isEqualTo("CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE");
        assertThat(itG.getElementsByTagName("contribuyenteRimpe").getLength()).isZero();
    }

    @Test
    void agenteRetencion_solo_aparece_si_la_empresa_lo_es() {
        Empresa sinAgente = empresa("RIMPE_EMPRENDEDOR", false);
        Empresa conAgente = empresa("GENERAL", true);

        Document a = FacturaXmlBuilder.construir(sinAgente, facturaBasica(sinAgente), List.of(detalle()));
        Document b = FacturaXmlBuilder.construir(conAgente, facturaBasica(conAgente), List.of(detalle()));

        Element itA = (Element) a.getElementsByTagName("infoTributaria").item(0);
        Element itB = (Element) b.getElementsByTagName("infoTributaria").item(0);

        assertThat(itA.getElementsByTagName("agenteRetencion").getLength()).isZero();
        assertThat(texto(itB, "agenteRetencion")).isEqualTo("1");
    }

    @Test
    void infoFactura_tiene_totales_formateados_con_2_decimales() {
        Comprobante f = facturaBasica(empresaDefault());
        f.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));

        Document doc = FacturaXmlBuilder.construir(empresaDefault(), f, List.of(detalle()));
        Element inf = (Element) doc.getElementsByTagName("infoFactura").item(0);

        assertThat(texto(inf, "totalSinImpuestos")).isEqualTo("100.00");
        assertThat(texto(inf, "totalDescuento")).isEqualTo("0.00");
        assertThat(texto(inf, "importeTotal")).isEqualTo("115.00");
        assertThat(texto(inf, "fechaEmision")).matches("\\d{2}/\\d{2}/\\d{4}");
        assertThat(texto(inf, "obligadoContabilidad")).isEqualTo("NO");
        assertThat(texto(inf, "tipoIdentificacionComprador")).isEqualTo("04");
    }

    @Test
    void totalConImpuestos_codifica_iva_15_correctamente() {
        Comprobante f = facturaBasica(empresaDefault());
        f.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));
        Document doc = FacturaXmlBuilder.construir(empresaDefault(), f, List.of(detalle()));
        Element ti = (Element) doc.getElementsByTagName("totalImpuesto").item(0);

        assertThat(texto(ti, "codigo")).isEqualTo("2");
        assertThat(texto(ti, "codigoPorcentaje")).isEqualTo("4");
        assertThat(texto(ti, "baseImponible")).isEqualTo("100.00");
        assertThat(texto(ti, "tarifa")).isEqualTo("15.00");
        assertThat(texto(ti, "valor")).isEqualTo("15.00");
    }

    @Test
    void totalConImpuestos_codifica_iva_0_cuando_no_hay_iva() {
        Comprobante f = facturaBasica(empresaDefault());
        f.totales(new BigDecimal("50.00"), BigDecimal.ZERO,
                  BigDecimal.ZERO, new BigDecimal("50.00"));
        Document doc = FacturaXmlBuilder.construir(empresaDefault(), f, List.of(detalle()));
        Element ti = (Element) doc.getElementsByTagName("totalImpuesto").item(0);

        assertThat(texto(ti, "codigoPorcentaje")).isEqualTo("0");
        assertThat(texto(ti, "tarifa")).isEqualTo("0.00");
        assertThat(texto(ti, "valor")).isEqualTo("0.00");
    }

    @Test
    void detalle_lleva_cantidad_y_precio_con_6_decimales_y_totales_con_2() {
        Document doc = construirFacturaSimple();
        Element det = (Element) doc.getElementsByTagName("detalle").item(0);

        assertThat(texto(det, "codigoPrincipal")).isEqualTo("PROD-001");
        assertThat(texto(det, "descripcion")).isEqualTo("Servicio de desarrollo web");
        assertThat(texto(det, "cantidad")).isEqualTo("1.000000");
        assertThat(texto(det, "precioUnitario")).isEqualTo("100.000000");
        assertThat(texto(det, "descuento")).isEqualTo("0.00");
        assertThat(texto(det, "precioTotalSinImpuesto")).isEqualTo("100.00");
    }

    @Test
    void detalle_impuesto_lleva_codigo_porcentaje_tarifa_base_valor() {
        Document doc = construirFacturaSimple();
        Element imp = (Element) doc.getElementsByTagName("impuesto").item(0);

        assertThat(texto(imp, "codigo")).isEqualTo("2");
        assertThat(texto(imp, "codigoPorcentaje")).isEqualTo("4");
        assertThat(texto(imp, "tarifa")).isEqualTo("15.00");
        assertThat(texto(imp, "baseImponible")).isEqualTo("100.00");
        assertThat(texto(imp, "valor")).isEqualTo("15.00");
    }

    @Test
    void multiples_detalles_se_generan_en_orden() {
        ComprobanteDetalle d1 = detalleCon("P1", "Item uno", new BigDecimal("100.00"));
        ComprobanteDetalle d2 = detalleCon("P2", "Item dos", new BigDecimal("50.00"));
        Document doc = FacturaXmlBuilder.construir(empresaDefault(),
            facturaBasica(empresaDefault()), List.of(d1, d2));

        NodeList detalles = doc.getElementsByTagName("detalle");
        assertThat(detalles.getLength()).isEqualTo(2);
        assertThat(texto((Element) detalles.item(0), "descripcion")).isEqualTo("Item uno");
        assertThat(texto((Element) detalles.item(1), "descripcion")).isEqualTo("Item dos");
    }

    @Test
    void infoAdicional_lleva_email_y_telefono_si_existen() {
        Comprobante f = facturaBasica(empresaDefault());
        f.receptor("04", "1790012345001", "Cliente S.A.",
                   "Av. Amazonas N123", "cliente@ejemplo.ec", "0991234567");
        Document doc = FacturaXmlBuilder.construir(empresaDefault(), f, List.of(detalle()));

        Element ia = (Element) doc.getElementsByTagName("infoAdicional").item(0);
        NodeList campos = ia.getElementsByTagName("campoAdicional");
        assertThat(campos.getLength()).isEqualTo(2);

        Element c0 = (Element) campos.item(0);
        Element c1 = (Element) campos.item(1);
        assertThat(c0.getAttribute("nombre")).isEqualTo("email");
        assertThat(c0.getTextContent()).isEqualTo("cliente@ejemplo.ec");
        assertThat(c1.getAttribute("nombre")).isEqualTo("telefono");
        assertThat(c1.getTextContent()).isEqualTo("0991234567");
    }

    @Test
    void infoAdicional_tiene_al_menos_un_campo_para_no_violar_xsd() {
        Comprobante f = facturaBasica(empresaDefault());
        f.receptor("07", "9999999999999", "CONSUMIDOR FINAL", null, null, null);
        Document doc = FacturaXmlBuilder.construir(empresaDefault(), f, List.of(detalle()));

        Element ia = (Element) doc.getElementsByTagName("infoAdicional").item(0);
        NodeList campos = ia.getElementsByTagName("campoAdicional");
        assertThat(campos.getLength()).isGreaterThan(0);
    }

    @Test
    void serializacion_es_xml_utf8_con_declaracion() {
        Document doc = construirFacturaSimple();
        byte[] xml = FacturaXmlBuilder.serializar(doc);
        String s = new String(xml, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(s).startsWith("<?xml");
        assertThat(s).contains("encoding=\"UTF-8\"");
        assertThat(s).contains("<factura id=\"comprobante\" version=\"2.1.0\">");
        assertThat(s).contains("<ruc>" + RUC_TARIUS + "</ruc>");
    }

    @Test
    void money_y_qty_formatean_correcto() {
        assertThat(FacturaXmlBuilder.money(new BigDecimal("100"))).isEqualTo("100.00");
        assertThat(FacturaXmlBuilder.money(new BigDecimal("100.5"))).isEqualTo("100.50");
        assertThat(FacturaXmlBuilder.money(new BigDecimal("100.555"))).isEqualTo("100.56");  // HALF_UP
        assertThat(FacturaXmlBuilder.money(null)).isEqualTo("0.00");

        assertThat(FacturaXmlBuilder.qty(new BigDecimal("1"))).isEqualTo("1.000000");
        assertThat(FacturaXmlBuilder.qty(new BigDecimal("2.5"))).isEqualTo("2.500000");
        assertThat(FacturaXmlBuilder.qty(null)).isEqualTo("0.000000");
    }

    @Test
    void leyenda_rimpe_segun_regimen() {
        assertThat(FacturaXmlBuilder.leyendaRimpe("RIMPE_NP"))
            .isEqualTo("CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE");
        assertThat(FacturaXmlBuilder.leyendaRimpe("RIMPE_EMPRENDEDOR"))
            .isEqualTo("CONTRIBUYENTE RÉGIMEN RIMPE");
        assertThat(FacturaXmlBuilder.leyendaRimpe("GENERAL")).isNull();
        assertThat(FacturaXmlBuilder.leyendaRimpe(null)).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers de construcción de fixtures
    // ─────────────────────────────────────────────────────────────────────

    private Document construirFacturaSimple() {
        Empresa e = empresaDefault();
        Comprobante f = facturaBasica(e);
        f.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));
        return FacturaXmlBuilder.construir(e, f, List.of(detalle()));
    }

    private Empresa empresaDefault() {
        return empresa("RIMPE_EMPRENDEDOR", false);
    }

    private Empresa empresa(String regimen, boolean agenteRetencion) {
        Empresa e = Empresa.nueva(RUC_TARIUS, "TARIUS S.A.S.");
        e.setDireccion("Av. Amazonas N34-451 y Av. Atahualpa, Quito, Ecuador");
        e.setRegimenTributario(regimen);
        e.setAgenteRetencion(agenteRetencion);
        e.setObligadoContabilidad(false);
        return e;
    }

    private Comprobante facturaBasica(Empresa emisor) {
        UUID empresaId = UUID.randomUUID();
        // El builder lee el RUC del emisor, no el ID — pero le seteo IDs reales para el shape
        Comprobante c = Comprobante.nuevo(
            empresaId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRUEBAS", 7L, "001-001-000000007",
            ClaveAccesoGenerator.generar(
                LocalDate.of(2026, 6, 20),
                ClaveAccesoGenerator.TipoDocumento.FACTURA,
                emisor.getRuc(),
                ClaveAccesoGenerator.Ambiente.PRUEBAS,
                "001", "001", 7L, "12345678"),
            LocalDate.of(2026, 6, 20));
        c.receptor("04", "1790012345001", "Cliente Demo S.A.",
                   "Av. NN 100", null, null);
        c.formaPago("01", 0);
        return c;
    }

    private ComprobanteDetalle detalle() {
        return detalleCon("PROD-001", "Servicio de desarrollo web", new BigDecimal("100.00"));
    }

    private ComprobanteDetalle detalleCon(String codigo, String descripcion, BigDecimal precio) {
        BigDecimal base = precio.setScale(2);
        BigDecimal iva = base.multiply(new BigDecimal("0.15")).setScale(2);
        return ComprobanteDetalle.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), 1,
            codigo, descripcion,
            new BigDecimal("1.000000"), precio.setScale(6),
            BigDecimal.ZERO, base,
            CodigoPorcentajeIva.IVA_15,
            base, iva);
    }

    private static String texto(Element padre, String tag) {
        NodeList nl = padre.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent();
    }
}
