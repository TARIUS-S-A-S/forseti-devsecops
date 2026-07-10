package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle.CodigoPorcentajeIva;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests del validador XSD SRI. El test más importante es {@link #factura_minima_generada_por_el_builder_pasa_xsd()}:
 * verifica que el contrato (FacturaXmlBuilder ↔ XSD) está alineado. Si algún día alguien
 * cambia el builder y rompe el shape, este test grita.
 */
class XsdValidatorTest {

    private static final String RUC_TARIUS = "1793235976001";

    private XsdValidator validator;

    @BeforeEach
    void setUp() {
        validator = new XsdValidator();
    }

    @Test
    void factura_minima_generada_por_el_builder_pasa_xsd() {
        Document doc = construirFactura(false, false);
        // No debe lanzar
        validator.validarFactura(doc);
    }

    @Test
    void factura_con_obligado_contabilidad_y_agente_retencion_pasa_xsd() {
        Document doc = construirFactura(true, true);
        validator.validarFactura(doc);
    }

    @Test
    void factura_iva_0_pasa_xsd() {
        Empresa e = empresaDefault();
        Comprobante f = facturaBasica(e);
        f.totales(new BigDecimal("50.00"), BigDecimal.ZERO,
                  BigDecimal.ZERO, new BigDecimal("50.00"));
        Document doc = FacturaXmlBuilder.construir(e, f, List.of(detalleIva0()));
        validator.validarFactura(doc);
    }

    @Test
    void factura_con_multiples_detalles_pasa_xsd() {
        Empresa e = empresaDefault();
        Comprobante f = facturaBasica(e);
        f.totales(new BigDecimal("300.00"), BigDecimal.ZERO,
                  new BigDecimal("45.00"), new BigDecimal("345.00"));
        var d1 = detalleCon("P1", "Item uno", new BigDecimal("100.00"));
        var d2 = detalleCon("P2", "Item dos", new BigDecimal("100.00"));
        var d3 = detalleCon("P3", "Item tres", new BigDecimal("100.00"));
        Document doc = FacturaXmlBuilder.construir(e, f, List.of(d1, d2, d3));
        validator.validarFactura(doc);
    }

    @Test
    void factura_serializada_como_bytes_tambien_valida() {
        Document doc = construirFactura(false, false);
        byte[] xml = FacturaXmlBuilder.serializar(doc);
        validator.validarFactura(xml);
    }

    @Test
    void xml_sin_root_factura_falla() {
        String xml = "<?xml version=\"1.0\"?><otro/>";
        assertThatThrownBy(() -> validator.validarFactura(xml.getBytes()))
            .isInstanceOf(XsdValidator.XsdValidationException.class);
    }

    @Test
    void xml_con_ruc_mal_formado_falla() {
        // RUC con menos de 13 dígitos → no debe pasar el pattern
        String xml = """
            <?xml version="1.0"?>
            <factura id="comprobante" version="2.1.0">
              <infoTributaria>
                <ambiente>1</ambiente>
                <tipoEmision>1</tipoEmision>
                <razonSocial>X</razonSocial>
                <ruc>123</ruc>
                <claveAcceso>1111111111111111111111111111111111111111111111111</claveAcceso>
                <codDoc>01</codDoc>
                <estab>001</estab>
                <ptoEmi>001</ptoEmi>
                <secuencial>1</secuencial>
                <dirMatriz>X</dirMatriz>
              </infoTributaria>
              <infoFactura>
                <fechaEmision>01/01/2026</fechaEmision>
                <tipoIdentificacionComprador>04</tipoIdentificacionComprador>
                <razonSocialComprador>X</razonSocialComprador>
                <identificacionComprador>X</identificacionComprador>
                <totalSinImpuestos>0.00</totalSinImpuestos>
                <totalDescuento>0.00</totalDescuento>
                <totalConImpuestos>
                  <totalImpuesto>
                    <codigo>2</codigo>
                    <codigoPorcentaje>0</codigoPorcentaje>
                    <baseImponible>0.00</baseImponible>
                    <valor>0.00</valor>
                  </totalImpuesto>
                </totalConImpuestos>
                <propina>0.00</propina>
                <importeTotal>0.00</importeTotal>
                <pagos><pago><formaPago>01</formaPago><total>0.00</total></pago></pagos>
              </infoFactura>
              <detalles>
                <detalle>
                  <codigoPrincipal>X</codigoPrincipal>
                  <descripcion>X</descripcion>
                  <cantidad>1.000000</cantidad>
                  <precioUnitario>0.000000</precioUnitario>
                  <descuento>0.00</descuento>
                  <precioTotalSinImpuesto>0.00</precioTotalSinImpuesto>
                  <impuestos>
                    <impuesto>
                      <codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>
                      <tarifa>0.00</tarifa><baseImponible>0.00</baseImponible><valor>0.00</valor>
                    </impuesto>
                  </impuestos>
                </detalle>
              </detalles>
            </factura>
            """;
        assertThatThrownBy(() -> validator.validarFactura(xml.getBytes()))
            .isInstanceOf(XsdValidator.XsdValidationException.class);
    }

    @Test
    void error_de_validacion_es_legible_en_el_mensaje() {
        // tipo identificación 99 no existe
        String xml = """
            <?xml version="1.0"?>
            <factura id="comprobante" version="2.1.0">
              <infoTributaria>
                <ambiente>1</ambiente><tipoEmision>1</tipoEmision>
                <razonSocial>X</razonSocial><ruc>1793235976001</ruc>
                <claveAcceso>1111111111111111111111111111111111111111111111111</claveAcceso>
                <codDoc>01</codDoc><estab>001</estab><ptoEmi>001</ptoEmi>
                <secuencial>1</secuencial><dirMatriz>X</dirMatriz>
              </infoTributaria>
              <infoFactura>
                <fechaEmision>01/01/2026</fechaEmision>
                <tipoIdentificacionComprador>99</tipoIdentificacionComprador>
                <razonSocialComprador>X</razonSocialComprador>
                <identificacionComprador>X</identificacionComprador>
                <totalSinImpuestos>0.00</totalSinImpuestos>
                <totalDescuento>0.00</totalDescuento>
                <totalConImpuestos>
                  <totalImpuesto>
                    <codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>
                    <baseImponible>0.00</baseImponible><valor>0.00</valor>
                  </totalImpuesto>
                </totalConImpuestos>
                <propina>0.00</propina>
                <importeTotal>0.00</importeTotal>
                <pagos><pago><formaPago>01</formaPago><total>0.00</total></pago></pagos>
              </infoFactura>
              <detalles>
                <detalle>
                  <codigoPrincipal>X</codigoPrincipal><descripcion>X</descripcion>
                  <cantidad>1.000000</cantidad><precioUnitario>0.000000</precioUnitario>
                  <descuento>0.00</descuento><precioTotalSinImpuesto>0.00</precioTotalSinImpuesto>
                  <impuestos>
                    <impuesto>
                      <codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>
                      <tarifa>0.00</tarifa><baseImponible>0.00</baseImponible><valor>0.00</valor>
                    </impuesto>
                  </impuestos>
                </detalle>
              </detalles>
            </factura>
            """;
        assertThatThrownBy(() -> validator.validarFactura(xml.getBytes()))
            .isInstanceOf(XsdValidator.XsdValidationException.class)
            // El mensaje XSD oficial menciona tipoIdentificacionComprador o el valor inválido
            .satisfies(ex -> assertThat(ex.getMessage()).isNotBlank());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────────────

    private Document construirFactura(boolean obligadoContabilidad, boolean agenteRetencion) {
        Empresa e = empresaDefault();
        e.setObligadoContabilidad(obligadoContabilidad);
        e.setAgenteRetencion(agenteRetencion);
        Comprobante f = facturaBasica(e);
        f.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));
        return FacturaXmlBuilder.construir(e, f, List.of(detalle()));
    }

    private Empresa empresaDefault() {
        Empresa e = Empresa.nueva(RUC_TARIUS, "TARIUS S.A.S.");
        e.setDireccion("Av. Amazonas N34-451 y Av. Atahualpa, Quito");
        e.setRegimenTributario("RIMPE_EMPRENDEDOR");
        return e;
    }

    private Comprobante facturaBasica(Empresa emisor) {
        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "FACTURA", "PRUEBAS", 1L, "001-001-000000001",
            ClaveAccesoGenerator.generar(
                LocalDate.of(2026, 6, 20),
                ClaveAccesoGenerator.TipoDocumento.FACTURA,
                emisor.getRuc(),
                ClaveAccesoGenerator.Ambiente.PRUEBAS,
                "001", "001", 1L, "12345678"),
            LocalDate.of(2026, 6, 20));
        c.receptor("04", "1790012345001", "Cliente Demo S.A.",
                   "Av. NN 100", "demo@ejemplo.ec", null);
        c.formaPago("01", 0);
        return c;
    }

    private ComprobanteDetalle detalle() {
        return detalleCon("PROD-001", "Servicio", new BigDecimal("100.00"));
    }

    private ComprobanteDetalle detalleIva0() {
        return ComprobanteDetalle.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), 1,
            "MED-001", "Medicamento (IVA 0%)",
            new BigDecimal("1.000000"), new BigDecimal("50.000000"),
            BigDecimal.ZERO, new BigDecimal("50.00"),
            CodigoPorcentajeIva.IVA_0,
            new BigDecimal("50.00"), BigDecimal.ZERO);
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
}
