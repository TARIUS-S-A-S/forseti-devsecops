package ec.tarius.forseti.emision.sri;

import ec.tarius.forseti.emision.domain.Comprobante;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle;
import ec.tarius.forseti.emision.domain.ComprobanteDetalle.CodigoPorcentajeIva;
import ec.tarius.forseti.empresa.domain.Empresa;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests anti-regresión Sprint 4 fixes:
 *   1) NotaCreditoXmlBuilder produce XML que pasa el XSD oficial v1.1.0.
 *   2) `contribuyenteEspecial` se OMITE cuando empresa.codigoContribuyenteEspecial es null/blank.
 *   3) `contribuyenteEspecial` se INCLUYE cuando empresa lo tiene seteado.
 *   4) Una NC sin motivo o codDocModificado falla la validación XSD (regresion guard).
 */
class NotaCreditoXmlBuilderXsdTest {

    private static final XsdValidator XSD = new XsdValidator();

    @Test
    void nc_valida_pasa_xsd_y_omite_contribuyenteEspecial_si_empresa_no_lo_tiene() {
        Empresa emisor = empresaSinContribEspecial();
        Comprobante nc = ncFirmable(emisor);
        Document xml = NotaCreditoXmlBuilder.construir(emisor, nc, List.of(detalle()));

        // 1) pasa XSD
        XSD.validarNotaCredito(xml);

        // 2) NO hay <contribuyenteEspecial>
        NodeList nodos = xml.getElementsByTagName("contribuyenteEspecial");
        assertThat(nodos.getLength())
            .as("Empresa sin codigo de contribuyente especial → elemento debe OMITIRSE")
            .isZero();
    }

    @Test
    void nc_con_contribuyenteEspecial_lo_incluye_y_pasa_xsd() {
        Empresa emisor = empresaSinContribEspecial();
        emisor.setCodigoContribuyenteEspecial("5368");
        Comprobante nc = ncFirmable(emisor);
        Document xml = NotaCreditoXmlBuilder.construir(emisor, nc, List.of(detalle()));

        XSD.validarNotaCredito(xml);

        NodeList nodos = xml.getElementsByTagName("contribuyenteEspecial");
        assertThat(nodos.getLength()).isEqualTo(1);
        assertThat(nodos.item(0).getTextContent()).isEqualTo("5368");
    }

    @Test
    void nc_con_contribuyenteEspecial_blank_no_lo_incluye() {
        Empresa emisor = empresaSinContribEspecial();
        emisor.setCodigoContribuyenteEspecial("   ");  // blank
        Comprobante nc = ncFirmable(emisor);
        Document xml = NotaCreditoXmlBuilder.construir(emisor, nc, List.of(detalle()));

        XSD.validarNotaCredito(xml);

        NodeList nodos = xml.getElementsByTagName("contribuyenteEspecial");
        assertThat(nodos.getLength())
            .as("Setter normaliza blank → null → omite elemento")
            .isZero();
    }

    @Test
    void nc_sin_codDocModificado_falla_xsd() {
        Empresa emisor = empresaSinContribEspecial();
        Comprobante nc = ncFirmable(emisor);
        Document xml = NotaCreditoXmlBuilder.construir(emisor, nc, List.of(detalle()));

        // Eliminar codDocModificado del DOM para simular bug en builder
        NodeList nodos = xml.getElementsByTagName("codDocModificado");
        if (nodos.getLength() > 0) {
            nodos.item(0).getParentNode().removeChild(nodos.item(0));
        }

        assertThatThrownBy(() -> XSD.validarNotaCredito(xml))
            .isInstanceOf(XsdValidator.XsdValidationException.class)
            .hasMessageContaining("notaCredito");
    }

    @Test
    void nc_con_motivo_muy_corto_falla_xsd() {
        Empresa emisor = empresaSinContribEspecial();
        Comprobante nc = ncFirmable(emisor);
        nc.notaCreditoSobre("01", "001-001-000000001", LocalDate.of(2026, 6, 1), "xx"); // 2 chars
        Document xml = NotaCreditoXmlBuilder.construir(emisor, nc, List.of(detalle()));

        assertThatThrownBy(() -> XSD.validarNotaCredito(xml))
            .isInstanceOf(XsdValidator.XsdValidationException.class);
    }

    // ─── fixtures ───────────────────────────────────────────────────────

    private Empresa empresaSinContribEspecial() {
        Empresa e = Empresa.nueva("1793235976001", "TARIUS S.A.S.");
        e.setDireccion("Av. Amazonas N34-451, Quito");
        e.setRegimenTributario("RIMPE_EMPRENDEDOR");
        e.setObligadoContabilidad(false);
        return e;
    }

    private Comprobante ncFirmable(Empresa emisor) {
        Comprobante c = Comprobante.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "NOTA_CREDITO", "PRUEBAS", 1L, "001-001-000000001",
            // claveAcceso: 49 dígitos exactos (validado por XSD pattern [0-9]{49})
            "2106202604179323597600110010010000000011234567819",
            LocalDate.of(2026, 6, 21));
        c.receptor("04", "1790012345001", "Cliente Demo S.A.",
                   "Av. NN 100", "demo@ejemplo.ec", null);
        c.totales(new BigDecimal("100.00"), BigDecimal.ZERO,
                  new BigDecimal("15.00"), new BigDecimal("115.00"));
        c.notaCreditoSobre("01", "001-001-000000099", LocalDate.of(2026, 6, 1),
                           "Devolucion por defecto del producto");
        return c;
    }

    private ComprobanteDetalle detalle() {
        return ComprobanteDetalle.nuevo(
            UUID.randomUUID(), UUID.randomUUID(), 1,
            "PROD-001", "Servicio de prueba para NC",
            new BigDecimal("1.000000"), new BigDecimal("100.000000"),
            BigDecimal.ZERO, new BigDecimal("100.00"),
            CodigoPorcentajeIva.IVA_15,
            new BigDecimal("100.00"), new BigDecimal("15.00"));
    }
}
