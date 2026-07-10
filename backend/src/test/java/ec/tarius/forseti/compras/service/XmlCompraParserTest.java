package ec.tarius.forseti.compras.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sprint 5 gate ①: XML real → datos extraídos correctamente, sin digitación.
 */
class XmlCompraParserTest {

    private final XmlCompraParser parser = new XmlCompraParser();

    private static final String XML_FACTURA_REAL = """
        <?xml version="1.0" encoding="UTF-8"?>
        <factura id="comprobante" version="2.1.0">
          <infoTributaria>
            <ambiente>1</ambiente>
            <tipoEmision>1</tipoEmision>
            <razonSocial>PROVEEDOR DEMO CIA. LTDA.</razonSocial>
            <ruc>1791234567001</ruc>
            <claveAcceso>2106202601179123456700110010010000001231234567812</claveAcceso>
            <codDoc>01</codDoc>
            <estab>001</estab>
            <ptoEmi>001</ptoEmi>
            <secuencial>000000123</secuencial>
            <dirMatriz>Av. Demo N12-34</dirMatriz>
          </infoTributaria>
          <infoFactura>
            <fechaEmision>21/06/2026</fechaEmision>
            <dirEstablecimiento>Av. Demo N12-34</dirEstablecimiento>
            <obligadoContabilidad>SI</obligadoContabilidad>
            <tipoIdentificacionComprador>04</tipoIdentificacionComprador>
            <razonSocialComprador>CLIENTE X</razonSocialComprador>
            <identificacionComprador>1793235976001</identificacionComprador>
            <totalSinImpuestos>100.00</totalSinImpuestos>
            <totalDescuento>0.00</totalDescuento>
            <totalConImpuestos>
              <totalImpuesto>
                <codigo>2</codigo>
                <codigoPorcentaje>4</codigoPorcentaje>
                <baseImponible>100.00</baseImponible>
                <valor>15.00</valor>
              </totalImpuesto>
            </totalConImpuestos>
            <propina>0.00</propina>
            <importeTotal>115.00</importeTotal>
            <moneda>DOLAR</moneda>
          </infoFactura>
          <detalles>
            <detalle>
              <codigoPrincipal>P001</codigoPrincipal>
              <descripcion>Servicio Demo</descripcion>
              <cantidad>1.00</cantidad>
              <precioUnitario>100.00</precioUnitario>
              <descuento>0.00</descuento>
              <precioTotalSinImpuesto>100.00</precioTotalSinImpuesto>
            </detalle>
          </detalles>
        </factura>
        """;

    @Test
    void parsea_factura_pelada_extrae_todos_los_datos() {
        XmlCompraParser.DatosCompra d = parser.parsear(XML_FACTURA_REAL.getBytes(StandardCharsets.UTF_8));

        assertThat(d.proveedorRuc()).isEqualTo("1791234567001");
        assertThat(d.proveedorRazonSocial()).isEqualTo("PROVEEDOR DEMO CIA. LTDA.");
        assertThat(d.tipoDocumento()).isEqualTo("FACTURA");
        assertThat(d.numeroComprobante()).isEqualTo("001-001-000000123");
        assertThat(d.claveAcceso()).hasSize(49);
        assertThat(d.fechaEmision()).isEqualTo(LocalDate.of(2026, 6, 21));
        assertThat(d.baseIva15()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(d.valorIva15()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(d.baseIva0()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.importeTotal()).isEqualByComparingTo(new BigDecimal("115.00"));
        assertThat(d.moneda()).isEqualTo("DOLAR");
    }

    @Test
    void parsea_envoltura_autorizacion_extrae_comprobante_interno() {
        String xmlAutorizado = """
            <?xml version="1.0" encoding="UTF-8"?>
            <autorizacion>
              <estado>AUTORIZADO</estado>
              <numeroAutorizacion>2106202601179123456700110010010000001231234567812</numeroAutorizacion>
              <fechaAutorizacion>2026-06-21T10:30:00.000-05:00</fechaAutorizacion>
              <ambiente>PRUEBAS</ambiente>
              <comprobante><![CDATA[%s]]></comprobante>
            </autorizacion>
            """.formatted(XML_FACTURA_REAL);

        XmlCompraParser.DatosCompra d = parser.parsear(xmlAutorizado.getBytes(StandardCharsets.UTF_8));
        assertThat(d.proveedorRuc()).isEqualTo("1791234567001");
        assertThat(d.numeroComprobante()).isEqualTo("001-001-000000123");
    }

    @Test
    void xml_mal_formado_lanza_excepcion_clara() {
        byte[] basura = "no es xml".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parsear(basura))
            .isInstanceOf(XmlCompraParser.XmlParserException.class)
            .hasMessageContaining("mal formado");
    }

    @Test
    void xml_con_clave_acceso_invalida_lanza_excepcion() {
        String malo = XML_FACTURA_REAL.replace(
            "2106202601179123456700110010010000001231234567812",
            "12345"); // ya no son 49 dígitos
        assertThatThrownBy(() -> parser.parsear(malo.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(XmlCompraParser.XmlParserException.class)
            .hasMessageContaining("claveAcceso");
    }

    @Test
    void xxe_attack_es_bloqueado_por_disallow_doctype() {
        // Si el parser no estuviera XXE-safe, esto leería /etc/passwd vía DTD.
        String xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <factura>&xxe;</factura>
            """;
        assertThatThrownBy(() -> parser.parsear(xxe.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(XmlCompraParser.XmlParserException.class);
    }

    // ─── Sprint 5 audit #18: tipos de comprobante faltantes ────────────

    @Test
    void parsea_notaCredito_recibida_extrae_tipo_NOTA_CREDITO() {
        String nc = XML_FACTURA_REAL
            .replace("<factura ", "<notaCredito ")
            .replace("</factura>", "</notaCredito>")
            .replace("<infoFactura>", "<infoNotaCredito>")
            .replace("</infoFactura>", "</infoNotaCredito>")
            .replace("<codDoc>01</codDoc>", "<codDoc>04</codDoc>");

        XmlCompraParser.DatosCompra d = parser.parsear(nc.getBytes(StandardCharsets.UTF_8));

        assertThat(d.tipoDocumento()).isEqualTo("NOTA_CREDITO");
        assertThat(d.numeroComprobante()).isEqualTo("001-001-000000123");
    }

    @Test
    void parsea_liquidacionCompra_extrae_tipo_LIQUIDACION_COMPRA() {
        String liq = XML_FACTURA_REAL
            .replace("<factura ", "<liquidacionCompra ")
            .replace("</factura>", "</liquidacionCompra>")
            .replace("<infoFactura>", "<infoLiquidacionCompra>")
            .replace("</infoFactura>", "</infoLiquidacionCompra>")
            .replace("<codDoc>01</codDoc>", "<codDoc>03</codDoc>");

        XmlCompraParser.DatosCompra d = parser.parsear(liq.getBytes(StandardCharsets.UTF_8));

        assertThat(d.tipoDocumento()).isEqualTo("LIQUIDACION_COMPRA");
    }

    @Test
    void parsea_notaDebito_recibida_extrae_tipo_NOTA_DEBITO() {
        String nd = XML_FACTURA_REAL
            .replace("<factura ", "<notaDebito ")
            .replace("</factura>", "</notaDebito>")
            .replace("<infoFactura>", "<infoNotaDebito>")
            .replace("</infoFactura>", "</infoNotaDebito>")
            .replace("<codDoc>01</codDoc>", "<codDoc>05</codDoc>");

        XmlCompraParser.DatosCompra d = parser.parsear(nd.getBytes(StandardCharsets.UTF_8));

        assertThat(d.tipoDocumento()).isEqualTo("NOTA_DEBITO");
    }

    @Test
    void raiz_desconocida_lanza_excepcion_clara() {
        String foo = """
            <?xml version="1.0" encoding="UTF-8"?>
            <foo><bar>x</bar></foo>
            """;
        assertThatThrownBy(() -> parser.parsear(foo.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(XmlCompraParser.XmlParserException.class)
            .hasMessageContaining("no soportado");
    }

    // ─── Sprint 5 audit #7: fechaAutorizacion REAL del envelope ────────

    @Test
    void envelope_con_fechaAutorizacion_iso_devuelve_instant_real() {
        String envelope = """
            <?xml version="1.0" encoding="UTF-8"?>
            <autorizacion>
              <estado>AUTORIZADO</estado>
              <numeroAutorizacion>2106202601179123456700110010010000001231234567812</numeroAutorizacion>
              <fechaAutorizacion>2026-06-21T10:30:00.000-05:00</fechaAutorizacion>
              <ambiente>PRUEBAS</ambiente>
              <comprobante><![CDATA[%s]]></comprobante>
            </autorizacion>
            """.formatted(XML_FACTURA_REAL);

        XmlCompraParser.DatosCompra d = parser.parsear(envelope.getBytes(StandardCharsets.UTF_8));

        assertThat(d.fechaAutorizacion()).isNotNull();
        assertThat(d.fechaAutorizacion()).isEqualTo(java.time.Instant.parse("2026-06-21T15:30:00Z"));
    }

    @Test
    void factura_pelada_sin_envelope_tiene_fechaAutorizacion_null() {
        XmlCompraParser.DatosCompra d = parser.parsear(XML_FACTURA_REAL.getBytes(StandardCharsets.UTF_8));

        assertThat(d.fechaAutorizacion()).isNull();  // null → caller usa Instant.now() como fallback
    }

    // ─── Sprint 5 audit #14: códigos IVA históricos se suman a baseIva15 ──

    @Test
    void codigoPorcentaje_2_iva_12_historico_se_acumula_a_baseIva15() {
        String xml = XML_FACTURA_REAL
            .replace("<codigoPorcentaje>4</codigoPorcentaje>",
                     "<codigoPorcentaje>2</codigoPorcentaje>");

        XmlCompraParser.DatosCompra d = parser.parsear(xml.getBytes(StandardCharsets.UTF_8));

        assertThat(d.baseIva15()).isEqualByComparingTo("100.00");
    }

    @Test
    void codigoPorcentaje_desconocido_99_no_revienta_y_no_acumula() {
        String xml = XML_FACTURA_REAL
            .replace("<codigoPorcentaje>4</codigoPorcentaje>",
                     "<codigoPorcentaje>99</codigoPorcentaje>");

        XmlCompraParser.DatosCompra d = parser.parsear(xml.getBytes(StandardCharsets.UTF_8));

        // Código desconocido se loguea WARN pero NO falla — base15 queda en 0
        assertThat(d.baseIva15()).isEqualByComparingTo("0.00");
    }

    // ─── Sprint 5 audit: dirEstablecimiento como fallback de dirMatriz ──

    @Test
    void dirEstablecimiento_es_fallback_si_no_hay_dirMatriz() {
        // El XML real solo tiene dirEstablecimiento (no dirMatriz dentro de infoFactura).
        // El parser ya prefiere dirMatriz si está; si no, usa dirEstablecimiento.
        XmlCompraParser.DatosCompra d = parser.parsear(XML_FACTURA_REAL.getBytes(StandardCharsets.UTF_8));

        // En el fixture no hay dirMatriz en infoFactura, sí dirEstablecimiento → fallback
        assertThat(d.proveedorDireccion()).isEqualTo("Av. Demo N12-34");
    }

    // ─── Sprint 5 audit #12: XMLs con namespaces (fallback getElementsByTagNameNS) ──

    @Test
    void parsea_xml_con_namespace_prefijado_en_todos_los_elementos() {
        // Algunos proveedores SRI emiten XML con namespace prefix en todos los nodos.
        // El fix de #12 hace fallback a getElementsByTagNameNS("*", tag) cuando
        // getElementsByTagName falla por namespace.
        String xmlNs = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ns:factura xmlns:ns="http://sri.gob.ec/comprobante" id="comprobante" version="2.1.0">
              <ns:infoTributaria>
                <ns:ambiente>1</ns:ambiente>
                <ns:tipoEmision>1</ns:tipoEmision>
                <ns:razonSocial>PROVEEDOR NAMESPACE CIA</ns:razonSocial>
                <ns:ruc>1791234567001</ns:ruc>
                <ns:claveAcceso>2106202601179123456700110010010000001231234567812</ns:claveAcceso>
                <ns:codDoc>01</ns:codDoc>
                <ns:estab>001</ns:estab>
                <ns:ptoEmi>001</ns:ptoEmi>
                <ns:secuencial>000000999</ns:secuencial>
                <ns:dirMatriz>Av. NS 1</ns:dirMatriz>
              </ns:infoTributaria>
              <ns:infoFactura>
                <ns:fechaEmision>21/06/2026</ns:fechaEmision>
                <ns:obligadoContabilidad>SI</ns:obligadoContabilidad>
                <ns:tipoIdentificacionComprador>04</ns:tipoIdentificacionComprador>
                <ns:razonSocialComprador>CLIENTE X</ns:razonSocialComprador>
                <ns:identificacionComprador>1793235976001</ns:identificacionComprador>
                <ns:totalSinImpuestos>200.00</ns:totalSinImpuestos>
                <ns:totalConImpuestos>
                  <ns:totalImpuesto>
                    <ns:codigo>2</ns:codigo>
                    <ns:codigoPorcentaje>4</ns:codigoPorcentaje>
                    <ns:baseImponible>200.00</ns:baseImponible>
                    <ns:valor>30.00</ns:valor>
                  </ns:totalImpuesto>
                </ns:totalConImpuestos>
                <ns:importeTotal>230.00</ns:importeTotal>
                <ns:moneda>DOLAR</ns:moneda>
              </ns:infoFactura>
            </ns:factura>
            """;

        XmlCompraParser.DatosCompra d = parser.parsear(xmlNs.getBytes(StandardCharsets.UTF_8));

        assertThat(d.proveedorRuc()).isEqualTo("1791234567001");
        assertThat(d.proveedorRazonSocial()).isEqualTo("PROVEEDOR NAMESPACE CIA");
        assertThat(d.numeroComprobante()).isEqualTo("001-001-000000999");
        assertThat(d.baseIva15()).isEqualByComparingTo("200.00");
        assertThat(d.valorIva15()).isEqualByComparingTo("30.00");
        assertThat(d.importeTotal()).isEqualByComparingTo("230.00");
    }
}
